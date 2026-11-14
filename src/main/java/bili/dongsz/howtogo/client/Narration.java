package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.store.RoutePreferenceStore;
import com.mojang.text2speech.Narrator;
import net.minecraft.client.Minecraft;
// 1.21.6 made the narrator's voice channel explicit at the call site.
//? if >=1.21.6 {
/*import net.minecraft.sounds.SoundSource;
*///?}
import net.minecraft.network.chat.Component;

/**
 * Spoken navigation announcements, through the text-to-speech engine Minecraft ships with.
 *
 * The engine directly rather than vanilla's GameNarrator, which gates every phrase on the player's narrator setting and throws it away when off; this mod's own toggle is the only switch.
 * The readout is recomputed every frame and its line carries a ticking distance, so speech is driven from the client tick and latched per manoeuvre: the approach line fires once when the junction enters the lead distance for the current pace, the "now" line once at the junction, and nothing repeats.
 * Guidance supersedes guidance -- the newest phrase replaces whatever waits and the engine purges what is being said. The trip's opening line is the one phrase that never goes out of date, so for about as long as it takes to say, nothing else is announced and the latches stay untouched (see spokenTicks()).
 * The engine's clear() and say() block until finished (up to half a second, measured), so the client tick only hands a phrase over under a short lock and one dedicated thread owns the engine. Everything else (latches, trip state, the readout flag) is touched by the client thread alone.
 * A client whose native voice library fails to load is a fault, not a setting: it is logged once and shown in the readout instead of silence. A Chinese default Windows voice can read English words but digits by its own rules ("49 m" as Chinese numerals); the library exposes no way to pick a voice, so the cure is setting a different Windows default voice.
 */
public final class Narration {

    private static final double MANEUVER_ID_SLACK = 2.0;

    /**
     * What a phrase is taken to cost before it is worth saying anything else over it. See spokenTicks(): say() is asynchronous and the next phrase purges it, so a phrase can only finish by being allowed the time.
     */
    private static final int SPOKEN_BASE_MS = 400;
    private static final int SPOKEN_SYLLABLE_MS = 200;
    private static final int SPOKEN_LETTER_MS = 60;
    private static final int SPOKEN_MIN_MS = 600;
    private static final int SPOKEN_MAX_MS = 6_000;

    private static final double MILLIS_PER_TICK = 50.0;

    /**
     * Guards pending, and is the monitor the speaking thread waits on. Held for one field assignment only; the speaking thread copies the phrase out and releases it before touching the engine.
     */
    private static final Object LOCK = new Object();
    /**
     * The one thread that owns the speech engine, or null until there is something to say. One thread rather than a pool, since two phrases at once would talk over each other; volatile so the client thread can read it without the lock.
     */
    private static volatile Thread speaker;
    /**
     * The phrase waiting to be spoken, or null. Guarded by LOCK and written by the client thread. One slot rather than a queue: instructions supersede each other and an out-of-date one is worse than none, so the newest replaces the waiting one and no backlog can grow.
     */
    private static String pending;
    private static volatile boolean engineKnown;
    private static volatile boolean engineActive;
    private static String lastSpoken;
    private static Destination session;
    private static double lastManeuverAt = Double.NaN;
    private static boolean aheadAnnounced;
    private static boolean nowAnnounced;
    /**
     * Whether the manoeuvre was already inside the lead distance when first seen. Decides which number the approach line says: the detection distance, or the measurement it tripped on. See approachDistance().
     */
    private static boolean aheadInsideLead;
    private static double lastUturnX = Double.NaN;
    private static double lastUturnZ = Double.NaN;
    private static boolean lastUturnHighway;
    private static boolean uturnAheadAnnounced;
    private static boolean uturnNowAnnounced;
    private static boolean uturnAheadInsideLead;
    private static boolean uturnPlainAnnounced;
    /**
     * The transit moment being announced: which ride and stop, and whether its two possible lines (approach and arrival) have been spoken. Keyed on ride and stop rather than on the sentence, which changes with distance and would look new every tick.
     */
    private static int transitRide = Integer.MIN_VALUE;
    private static int transitStop = Integer.MIN_VALUE;
    private static boolean transitAheadAnnounced;
    private static boolean transitNowAnnounced;
    private static boolean transitArrivedAnnounced;
    private static int roadClassChanges;
    private static boolean arrivalAnnounced;
    private static boolean offRouteAnnounced;
    /** Whether the failed-engine line has been logged for the current occurrence. */
    private static boolean unavailableLogged;
    /** Whether the readout should carry the "no speech engine" hint. */
    private static boolean unavailable;
    /** The client tick this update is on, so a phrase can be held for a length of time. */
    private static int ticks;
    /**
     * The tick the trip's opening line is left to say itself until, or a tick already past. See update().
     */
    private static int openingUntil;

    private Narration() {
    }

    /**
     * Speaks one phrase, unless it is the one already being spoken.
     *
     * The single entry point for everything this mod says; silent when announcements are off and on a repeat. Hands the phrase to the speaking thread and returns -- this runs on the client tick and must never touch the engine.
     */
    public static void announce(String phrase) {
        try {
            speak(phrase);
        } catch (Throwable t) {
            // Called from the client tick, where an escaping exception takes the game down; a phrase that cannot
            // be spoken should cost a log line, not the session.
            HowToGo.LOGGER.error("[HowToGo] could not announce '{}'", phrase, t);
        }
    }

    /**
     * One narration update per client tick.
     *
     * A tick rather than a frame, and driven from the tick rather than the HUD renderer: the renderer only runs with nothing open, and announcements must keep working while the fullscreen map is up.
     */
    public static void tick() {
        try {
            update();
        } catch (Throwable t) {
            HowToGo.LOGGER.error("[HowToGo] narration update failed", t);
        }
    }

    /** Whether the readout should say that the speech engine could not be loaded. */
    public static boolean unavailable() {
        return unavailable;
    }


    private static void update() {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !RoutePreferenceStore.voiceAnnouncements()) {
            reset();
            return;
        }
        // Started as soon as announcements are on rather than on the first phrase, so the engine's health is
        // known before anything is said; a volatile read once the thread exists.
        ensureSpeaker();
        noteEngine();

        Destination target = Navigation.target();
        if (target != session) {
            // A different trip: what was said on the last one must not silence this one, or a second trip to the
            // same place would arrive in silence and inherit the last trip's latch.
            session = target;
            lastSpoken = null;
            arrivalAnnounced = false;
            offRouteAnnounced = false;
            forgetManeuver();
            resetUturn();
            resetTransit();
            openingUntil = 0;
            // The road class count is the session's, not the notice's: baseline it so a change from before this
            // trip is not announced at its start.
            roadClassChanges = Navigation.classChangeCount();
            if (target != null) {
                // The trip's opening line, and the first thing heard after a destination is picked.
                //
                // Said here because this is the one branch that knows a trip has just begun, and because lastSpoken
                // was cleared a line earlier. It is not said twice: picking the destination already being navigated
                // leaves the session alone. The switch is respected by the guard at the top of the method.
                String opening = Component.translatable("hud.howtogo.speak_start").getString();
                announce(opening);
                // And then left alone for about as long as it takes to say.
                //
                // The opening line is preamble, not guidance -- nothing about it goes out of date -- so the one
                // thing that must not happen is being talked over. say() is asynchronous and the next phrase purges
                // what is being said, so a notice one tick later cuts it off mid-word. Nothing below runs while the
                // hold lasts, so latches are untouched rather than set and a junction that comes due is announced
                // when it lifts. Skipped when the engine is known dead.
                openingUntil = unavailable ? 0 : ticks + spokenTicks(opening);
            }
        }
        if (target == null) {
            return;
        }
        if (ticks < openingUntil) {
            return;
        }

        // Before the guidance, so a junction coming up on the same tick ends as the phrase left in the
        // queue: a road-surface change is worth a word, not talking over a turn.
        announceRoadClassChange();

        if (Navigation.showArrival()) {
            if (!arrivalAnnounced) {
                arrivalAnnounced = true;
                // Nothing else is worth saying over the arrival, and the readout has stopped showing turns by now.
                //
                // The closing line names no destination deliberately: the banner already says which place this is,
                // and the phrase is heard once at the end of a trip.
                announce(Component.translatable("hud.howtogo.speak_arrived").getString());
            }
            return;
        }
        arrivalAnnounced = false;

        if (Navigation.isOffRoute()) {
            if (!offRouteAnnounced) {
                offRouteAnnounced = true;
                announce(Component.translatable("hud.howtogo.speak_off_route").getString());
            }
        } else {
            // Cleared rather than latched for good: leaving the route a second time is news again.
            offRouteAnnounced = false;
        }

        if (!Navigation.route().isPresent()) {
            resetUturn();
            return;
        }
        // While there is a line to be on -- walking to the boarding stop, or riding one -- the transit cue
        // is announced and nothing else, since a vehicle does the steering. Walking legs fall through to the
        // ordinary guidance, so wrong-way and turn calls remain reachable; the off-route warning above still
        // applies.
        Navigation.TransitStep transit = Navigation.transitStep();
        if (transit != null) {
            announceTransit(transit);
            return;
        }
        resetTransit();
        // The wrong-way call takes precedence, and while it is up ordinary turns are not announced at all.
        Navigation.Uturn uturn = Navigation.wrongWayUturn();
        if (uturn != null) {
            announceUturn(uturn);
            return;
        }
        if (Navigation.wrongWay()) {
            // Going the wrong way with no junction named: the plain U-turn, never the route's next turn.
            announceUturnWithoutJunction();
            return;
        }
        // Going the right way again disarms the wrong-way latch, so a second mistake is announced as readily
        // as the first.
        resetUturn();
        announceTurn(Navigation.nextManeuver());
    }

    /**
     * About how long a phrase takes to say, in client ticks.
     * The library exposes no way to ask: its API is say, clear, active and destroy, say hands the text to SAPI with SPF_ASYNC, and the next phrase purges the previous one. So a caller that wants a phrase to finish must allow it time derived from the text. The count is per script -- Han, kana and Hangul as syllables, everything else as letters -- because a phrase's length is all there is to go on and scripts price differently. Digits are counted with letters; the voice's language is invisible to this mod (see the class comment). A floor and a ceiling keep the hold usable: too short and the protected phrase is still cut, too long and the guidance arrives late. The hold also gives the next phrase its pause, so two sentences do not sound run together. Package-private so the harness can check it: this is the one part of the hold that is a decision rather than a clock, and a hold too short sounds exactly like the bug it exists to fix.
     */
    static int spokenTicks(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return ticksFor(SPOKEN_MIN_MS);
        }
        int syllables = 0;
        int letters = 0;
        for (int i = 0; i < phrase.length(); ) {
            int character = phrase.codePointAt(i);
            i += Character.charCount(character);
            switch (Character.UnicodeScript.of(character)) {
                case HAN, HIRAGANA, KATAKANA, HANGUL -> syllables++;
                default -> {
                    if (Character.isLetterOrDigit(character)) {
                        letters++;
                    }
                }
            }
        }
        long millis = (long) SPOKEN_BASE_MS + (long) syllables * SPOKEN_SYLLABLE_MS
                + (long) letters * SPOKEN_LETTER_MS;
        // Math.clamp(long, int, int) arrived in Java 21; the 1.20.1 and 1.20.4 nodes are Java 17. Same
        // result, different JDK, hence a condition on the Minecraft version rather than the loader.
        //? if >=1.20.5 {
        return ticksFor(Math.clamp(millis, SPOKEN_MIN_MS, SPOKEN_MAX_MS));
        //?} else {
        /*return ticksFor(Math.max(SPOKEN_MIN_MS, Math.min(SPOKEN_MAX_MS, (int) millis)));
        *///?}
    }

    /** A length of time as client ticks, never less than one. */
    private static int ticksFor(long millis) {
        return Math.max(1, (int) Math.round(millis / MILLIS_PER_TICK));
    }

    /**
     * Says the plain U-turn, for a wrong-way reading that could not name a junction.
     *
     * Once per episode: resetUturn() disarms the latch every tick the call is not up, so a later mistake is spoken again while a run of ticks says this once.
     */
    private static void announceUturnWithoutJunction() {
        if (uturnPlainAnnounced) {
            return;
        }
        uturnPlainAnnounced = true;
        // The distance-free form, which needs no junction: naming one would be worse than saying nothing
        // about where.
        announce(Navigation.uturnSentence(Double.NaN, true, Navigation.onHighway()));
    }

    /**
     * Says a word when the road underfoot becomes a different kind of road.
     *
     * An event, not guidance, kept out of every latch above: it raises no manoeuvre and cannot move what the turn or U-turn announcements point at. Spoken once per change because the count it watches only moves on a change.
     */
    private static void announceRoadClassChange() {
        int changes = Navigation.classChangeCount();
        if (changes == roadClassChanges) {
            return;
        }
        roadClassChanges = changes;
        announce(Component.translatable("hud.howtogo.speak.class_change",
                Navigation.roadClassLabel(Navigation.classEntered())).getString());
    }

    /**
     * Announces the turn ahead once inside the lead distance, then again when due.
     * The phrase carries the distance and changes every step, so the latch is per manoeuvre rather than per phrase: a manoeuvre arms when it becomes next, fires the approach line once, the "now" line once, then stays quiet. The approach line says the detection distance (the lead distance for the pace), not the measurement that tripped it -- the call fires on the first tick inside the lead, which is a different number each time. The exception is a manoeuvre first seen already inside the lead, where the distance actually left is the honest number (see approachDistance()). Identity is position along the route (distanceAhead plus travelled), compared with a block of slack for rebasing; the step count is not available.
     */
    private static void announceTurn(Navigation.Instruction maneuver) {
        if (maneuver == null) {
            return;
        }
        double distance = maneuver.distanceAhead();
        double lead = Navigation.turnLeadDistance();
        double alongRoute = distance + Navigation.travelled();
        if (!(Math.abs(alongRoute - lastManeuverAt) <= MANEUVER_ID_SLACK)) {
            // A different junction, so both announcements are news again. Written as a negation so the first
            // manoeuvre of a trip arms rather than being skipped.
            lastManeuverAt = alongRoute;
            aheadAnnounced = false;
            nowAnnounced = false;
            aheadInsideLead = distance <= lead;
        }

        boolean now = distance <= Navigation.turnNowDistance();
        // Wording comes from Navigation, so voice and readout cannot describe the same turn differently,
        // including a U-turn worded differently on a highway.
        if (now) {
            if (!nowAnnounced) {
                nowAnnounced = true;
                // Once the turn is called, the approach line for the same junction is stale even if a re-plan pushes
                // the distance back out.
                aheadAnnounced = true;
                announce(Navigation.maneuverSentence(maneuver, true));
            }
            return;
        }
        if (aheadAnnounced || distance > lead) {
            return;
        }
        aheadAnnounced = true;
        announce(Navigation.maneuverSentence(
                atDetectionDistance(maneuver, approachDistance(distance, aheadInsideLead)), false));
    }

    /**
     * The distance an approach line says, given the measurement it fired on: the detection distance normally, the measurement itself when the manoeuvre was already inside the lead. See announceTurn().
     */
    private static double approachDistance(double measured, boolean seenInsideLead) {
        return seenInsideLead ? measured : Navigation.turnLeadDistance();
    }

    /**
     * The same manoeuvre with the distance the spoken line reports.
     *
     * Only the number is replaced: the angle, road and name flags decide the sentence and must be the readout's. The readout keeps the measured distance, being a countdown, which is why this copy is made for the voice.
     */
    private static Navigation.Instruction atDetectionDistance(Navigation.Instruction maneuver,
                                                              double distance) {
        return new Navigation.Instruction(distance, maneuver.turnDegrees(), maneuver.roadName(),
                maneuver.namesTheRoad(), maneuver.continuesNamedRoad(), maneuver.straight());
    }

    /**
     * Announces the wrong-way call once inside the lead distance, then again when due.
     *
     * Its own latch, because it is not a manoeuvre on the route: the junction it points at is its identity. Comparing the junction means carrying on past it calls the next one as new, while a noisy tick that moves nothing does not.
     */
    private static void announceUturn(Navigation.Uturn uturn) {
        double junctionX = uturn.junctionX();
        double junctionZ = uturn.junctionZ();
        double distance = uturn.distanceAhead();
        double lead = Navigation.turnLeadDistance();
        if (Double.isNaN(lastUturnX)
                || Math.hypot(junctionX - lastUturnX, junctionZ - lastUturnZ) > MANEUVER_ID_SLACK
                || uturn.highway() != lastUturnHighway) {
            // A different junction, or the same one under a different rule, so both lines are news again.
            // Compared against the remembered junction, not the distance, which changes every step. NaN is the
            // first call of an episode.
            lastUturnX = junctionX;
            lastUturnZ = junctionZ;
            lastUturnHighway = uturn.highway();
            uturnAheadAnnounced = false;
            uturnNowAnnounced = false;
            uturnAheadInsideLead = distance <= lead;
            // The same-text guard is re-armed with the latch: the "now" line carries no distance and is the same
            // sentence every time.
            lastSpoken = null;
        }

        boolean now = distance <= Navigation.turnNowDistance();
        if (now) {
            if (!uturnNowAnnounced) {
                uturnNowAnnounced = true;
                // The approach line for this junction is stale once the now line has been said.
                uturnAheadAnnounced = true;
                announce(Navigation.uturnSentence(distance, true, uturn.highway()));
            }
            return;
        }
        if (uturnAheadAnnounced || distance > lead) {
            return;
        }
        uturnAheadAnnounced = true;
        // The same detection-distance rule as the ordinary turn line, for the same reason: the firing
        // distance is a measurement, the line is guidance.
        announce(Navigation.uturnSentence(approachDistance(distance, uturnAheadInsideLead), false,
                uturn.highway()));
    }

    /**
     * Disarms the wrong-way latch, so going the wrong way again is announced again. Called every tick the call is not up, which makes the re-arming automatic.
     */
    private static void resetUturn() {
        lastUturnX = Double.NaN;
        lastUturnZ = Double.NaN;
        uturnAheadAnnounced = false;
        uturnNowAnnounced = false;
        uturnAheadInsideLead = false;
        uturnPlainAnnounced = false;
    }

    /**
     * Announces the transit moment the guidance is on: boarding, a stop called at, or the stop to leave.
     * Three moments, each said once: boarding (line and direction, so the rider can check the platform), a stop called at (where we are and how many stops remain, driven by stop index rather than distance so it does not repeat while the vehicle crawls up), and the stop being left (twice: on the approach and standing at it -- the approach is the first warning on a long ride, the stop itself is where the player acts). Identity is the ride and the stop within it, so a phrase that changes with the distance reads as the same moment rather than news every tick.
     */
    private static void announceTransit(Navigation.TransitStep step) {
        if (step.rideIndex() != transitRide) {
            // A different ride: its stops are news again and both lines are armed from scratch.
            transitRide = step.rideIndex();
            transitStop = Integer.MIN_VALUE;
            transitAheadAnnounced = false;
            transitNowAnnounced = false;
            transitArrivedAnnounced = false;
        }

        // Standing at the stop this ride is left at: the one moment the player must act, and one the
        // guidance used to miss. Where the journey changes lines, it names the line being changed onto here
        // too.
        if (step.cue() == Navigation.TransitCue.ARRIVE) {
            if (!transitArrivedAnnounced) {
                transitArrivedAnnounced = true;
                announce(Navigation.transitSentence(step));
            }
            return;
        }

        // Arriving at a stop that is not the one this ride is left at: where we are and what is left. The
        // boarding stop and the alighting stop are skipped -- the boarding line just named the first, the
        // approach line covers the second.
        if (step.stopIndex() > transitStop) {
            transitStop = step.stopIndex();
            if (step.stopIndex() > 0 && step.stopsRemaining() > 0) {
                announce(Component.translatable("hud.howtogo.speak_stopped",
                        Navigation.named(step.reached()), step.stopsRemaining(),
                        Navigation.named(step.station())).getString());
            }
        }

        if (step.cue() == Navigation.TransitCue.BOARD) {
            if (step.distanceAhead() <= Navigation.turnNowDistance() && !transitNowAnnounced) {
                transitNowAnnounced = true;
                announce(Component.translatable("hud.howtogo.speak_board_line",
                        Navigation.named(step.station()), Navigation.named(step.line()),
                        Navigation.named(step.terminus())).getString());
            }
            return;
        }
        if (step.cue() == Navigation.TransitCue.ALIGHT) {
            // Said on the approach, once: the transit lines carry no distance, so the detection-distance rule
            // has nothing to change. "Arriving at X" is as true at the lead distance as a tick later.
            if (transitAheadAnnounced || step.distanceAhead() > Navigation.turnLeadDistance()) {
                return;
            }
            transitAheadAnnounced = true;
            announce(Navigation.transitSentence(step));
        }
    }

    private static void resetTransit() {
        transitRide = Integer.MIN_VALUE;
        transitStop = Integer.MIN_VALUE;
        transitAheadAnnounced = false;
        transitNowAnnounced = false;
        transitArrivedAnnounced = false;
    }


    /**
     * Hands a phrase to the speaking thread, or drops it. Nanoseconds and no engine call: the tick this runs on must stay smooth -- a string comparison, one lock acquisition and a field write.
     */
    private static void speak(String phrase) {
        if (phrase == null || phrase.isBlank() || !RoutePreferenceStore.voiceAnnouncements()) {
            return;
        }
        if (phrase.equals(lastSpoken)) {
            return;
        }
        // Recorded here rather than after the engine has said it: the dedup is about what has been queued,
        // and the speaking thread may replace this phrase with a newer one first.
        lastSpoken = phrase;
        ensureSpeaker();
        synchronized (LOCK) {
            // Newest wins: the slot is overwritten rather than queued behind, so no backlog forms and an
            // out-of-date instruction is never spoken after a newer one.
            pending = phrase;
            LOCK.notifyAll();
        }
    }

    private static void ensureSpeaker() {
        if (speaker != null) {
            return;
        }
        synchronized (LOCK) {
            if (speaker != null) {
                return;
            }
            Thread thread = new Thread(Narration::speakLoop, "HowToGo narration");
            // A daemon, so a stuck voice can never hold the game open at shutdown.
            thread.setDaemon(true);
            speaker = thread;
            thread.start();
        }
    }

    /**
     * The speaking thread's whole life: build the engine once, then say whatever is waiting.
     *
     * Built here rather than on the client thread because construction is as slow as use, and on Windows the voice is a COM object that belongs to the thread that created it.
     */
    private static void speakLoop() {
        Narrator narrator = null;
        boolean active = false;
        try {
            // Whatever voice Windows has as its default; the library offers no way to choose one. See the class
            // comment.
            narrator = Narrator.getNarrator();
            active = narrator.active();
        } catch (Throwable t) {
            HowToGo.LOGGER.error("[HowToGo] could not build the speech engine", t);
        }
        engineActive = active;
        engineKnown = true;

        while (true) {
            String phrase = take();
            if (phrase == null) {
                // Interrupted while waiting: the thread has been asked to stop.
                return;
            }
            if (!active) {
                continue;
            }
            try {
                // Debug, off in normal play: the exact text handed to the engine, so odd pronunciation reports can
                // be checked. It cannot show which voice read it.
                HowToGo.diagnostic("[HowToGo] speaking: {}", phrase);
                // The same sequence the vanilla wrapper uses: drop what is being said, then interrupt -- a turn
                // prompt makes the previous instruction out of date.
                narrator.clear();
                // 1.21.6 made the voice channel explicit: say() takes the volume, since the narrator has no stream
                // to infer it from. Asking the options for the voice channel matches vanilla, so a player who turned
                // voice down is respected.
                //? if >=1.21.6 {
                /*narrator.say(phrase, true, Minecraft.getInstance().options
                        .getFinalSoundSourceVolume(SoundSource.VOICE));
                *///?} else {
                narrator.say(phrase, true);
                //?}
            } catch (Throwable t) {
                // The loop must not die: a dead speaker is silence with no explanation while the readout still
                // claims the engine is fine.
                HowToGo.LOGGER.error("[HowToGo] could not speak '{}'", phrase, t);
            }
        }
    }

    /**
     * Waits for the next phrase and takes it. The lock is held only to read and clear the slot, never while speaking, so the client thread can only ever wait for that.
     */
    private static String take() {
        synchronized (LOCK) {
            while (pending == null) {
                try {
                    LOCK.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            String phrase = pending;
            pending = null;
            return phrase;
        }
    }

    /**
     * Records whether the engine came up, from the flags the speaking thread published. Reads two volatiles and never touches the engine, so the client tick never blocks on a native call.
     */
    private static void noteEngine() {
        if (!engineKnown) {
            // The speaking thread has not finished building it yet: say nothing rather than claiming it is
            // broken while starting.
            unavailable = false;
            return;
        }
        unavailable = !engineActive;
        if (!unavailable) {
            // Reset, so a client that loses the engine later is reported again.
            unavailableLogged = false;
            return;
        }
        if (!unavailableLogged) {
            unavailableLogged = true;
            HowToGo.LOGGER.warn(
                    "[HowToGo] voice announcements are on, but the text-to-speech engine could "
                            + "not be loaded, so nothing will be heard; see the narrator load error "
                            + "above");
        }
    }

    /**
     * Forgets which manoeuvre was being announced, so the next one is armed from scratch. A re-planned junction needs no reset -- its moved position already reads as a different manoeuvre. This covers the cases with no position left to compare: a new trip, or announcements off and on.
     */
    private static void forgetManeuver() {
        lastManeuverAt = Double.NaN;
        aheadAnnounced = false;
        nowAnnounced = false;
        aheadInsideLead = false;
    }

    private static void reset() {
        unavailable = false;
        lastSpoken = null;
        session = null;
        arrivalAnnounced = false;
        offRouteAnnounced = false;
        forgetManeuver();
        resetUturn();
        resetTransit();
        // Any hold goes with the trip it was for; a hold outliving its destination would silence the next
        // trip's guidance for a phrase nobody heard.
        openingUntil = 0;
        // Baseline the class count too: changes that passed while announcements were off are not news when
        // they come back.
        roadClassChanges = Navigation.classChangeCount();
        // Anything still waiting goes too: the switch is off or there is nobody to speak to, so a phrase
        // handed over a moment ago should not be said now.
        synchronized (LOCK) {
            pending = null;
        }
    }
}
