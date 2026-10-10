package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.Route;
import bili.dongsz.howtogo.store.RoutePreferenceStore;
import com.mojang.text2speech.Narrator;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Spoken navigation announcements, through the text-to-speech engine Minecraft ships with.
 *
 * <p>The engine directly rather than vanilla's {@code GameNarrator}: that wrapper gates every phrase
 * on the player's own narrator setting and throws the phrase away when it is off, so announcements
 * would depend on a second switch the player never connected to this mod. This mod's own toggle is
 * therefore the only switch, and the speech is what the player asked for either way.
 *
 * <h2>Why the state is explicit</h2>
 * The navigation readout is recomputed every frame, so a turn instruction "changes" twenty times a
 * second as its distance ticks down -- and the spoken line carries that distance, so its text is no
 * guide at all. What is spoken is instead driven from the client tick and remembered per manoeuvre:
 * the approach line fires once as the junction comes within the lead distance for the pace being
 * made, the "now" line fires once at the junction, and nothing repeats in between.
 *
 * <p>A silent client is still possible -- the speech library is a native one and can fail to load --
 * but that is a fault rather than a setting, so it is reported once in the log and stated in the
 * readout instead of being left as unexplained silence.
 *
 * <h2>Why the opening line is left alone, and nothing else is</h2>
 * Guidance supersedes guidance: a turn prompt that arrives after the turn is noise, so the newest
 * phrase replaces whatever is waiting and the engine is told to purge whatever is being said. The
 * trip's opening line is the one phrase that is not guidance and never goes out of date, so for about
 * as long as it takes to say, nothing else is announced at all -- neither handed over nor allowed to
 * set the latches that decide what has been announced. That is what makes the road notice and the
 * first junction follow it rather than talk over it, which is what they were doing: the engine says a
 * phrase without waiting for it, and the next phrase purges it, so a line handed over one tick later
 * cuts the opening words off mid-word and the two are heard as one sentence. See
 * {@link #spokenTicks} for why "as long as it takes" has to be estimated rather than asked for.
 *
 * <h2>Why the speaking happens on another thread</h2>
 * The engine is a native one whose {@code clear()} and {@code say()} block until it has finished:
 * measured in play, a single call held the client thread for up to half a second. That is the stutter
 * the player sees at junctions, because junctions are exactly when a phrase is spoken. So the client
 * tick only ever hands a phrase over -- a string assignment under a lock nobody holds for longer than
 * it takes to read a field -- and one dedicated thread owns the engine and does the blocking work.
 *
 * <p>What crosses between the two threads is deliberately tiny and is listed on the fields below.
 * Everything else here -- the latch, the trip state, the readout's "no engine" flag -- is touched by
 * the client thread alone, which is also the thread that renders, so it needs no synchronisation.
 *
 * <h2>Why an English announcement can be read with Chinese numbers</h2>
 * Reported in play: with the game in English, "In 49 m, turn right onto unnamed road" was heard with
 * the number and the unit in Chinese -- "四十九米". The text is not the cause: on an English client
 * the whole sentence is English ASCII, because every part of it comes from {@code en_us.json} together
 * with {@link Route#formatDistance}, which writes the number as digits and takes the rest of its
 * wording from the same language files. The engine is the cause, and it has exactly one voice:
 * {@code Narrator.getNarrator()} creates the SAPI voice object through
 * {@code CoCreateInstance(CLSID_SpVoice)}, which is whatever Windows has as its default, and the
 * library exposes no way to ask for another one -- its whole API is say, clear, active, destroy and
 * getNarrator. A Chinese default voice reads the English words as English but the digits by its own
 * language's rules, which is where "49 m" becomes 四十九米.
 *
 * <p>So the cure is outside the mod: set a different default voice for Windows (Settings &gt; Time
 * &amp; Language &gt; Speech &gt; Voice), which is what every SAPI caller on the machine hears, vanilla
 * narration included. Writing the number as words would not help either: the digits are the half the
 * voice gets wrong, and it would still be the same voice reading them.
 */
public final class Narration {

    /** How far two readings of a manoeuvre's position may differ and still be the same manoeuvre. */
    private static final double MANEUVER_ID_SLACK = 2.0;

    /**
     * What a phrase is taken to cost before it is worth saying anything else over it.
     *
     * <p>See {@link #spokenTicks}: the engine says a phrase asynchronously and purges whatever is
     * still being said when the next one arrives, so the only way to let a phrase finish is to wait
     * for about as long as it takes.
     */
    private static final int SPOKEN_BASE_MS = 400;
    private static final int SPOKEN_SYLLABLE_MS = 200;
    private static final int SPOKEN_LETTER_MS = 60;
    private static final int SPOKEN_MIN_MS = 600;
    private static final int SPOKEN_MAX_MS = 6_000;

    /** One client tick, which is the clock {@link #tick()} runs on. */
    private static final double MILLIS_PER_TICK = 50.0;

    /**
     * Guards {@link #pending}, and is the monitor the speaking thread waits on.
     *
     * <p>It is held for the length of a field assignment and nothing else: the speaking thread copies
     * the phrase out and releases it before it touches the engine, so a client tick can never queue
     * behind a phrase being spoken.
     */
    private static final Object LOCK = new Object();
    /**
     * The one thread that owns the speech engine, or null until there is something to say.
     *
     * <p>One thread rather than a pool, because two phrases spoken at once would talk over each
     * other. Volatile so the client thread can see it on the cheap path without taking the lock.
     */
    private static volatile Thread speaker;
    /**
     * The phrase waiting to be spoken, or null.
     *
     * <p>Guarded by {@link #LOCK} and written by the client thread. One slot rather than a queue:
     * instructions supersede each other, and an out-of-date one is worse than none -- a turn prompt
     * that arrives after the turn is noise. So the newest phrase replaces whatever was waiting, and
     * the backlog can never grow.
     */
    private static String pending;
    /** Whether the speaking thread has finished building the engine, set once by that thread. */
    private static volatile boolean engineKnown;
    /** Whether that engine came up, published by the speaking thread for the readout to read. */
    private static volatile boolean engineActive;
    /** The phrase spoken last, so the same sentence is not repeated while it stays true. */
    private static String lastSpoken;
    /** The trip the repeat state belongs to, so a new destination starts from a clean slate. */
    private static Destination session;
    /** Where along the route the manoeuvre being announced sits, or NaN when there is none. */
    private static double lastManeuverAt = Double.NaN;
    /** Whether the approach line has been spoken for the manoeuvre being announced. */
    private static boolean aheadAnnounced;
    /** Whether the "now" line has been spoken for the manoeuvre being announced. */
    private static boolean nowAnnounced;
    /** Where the junction the wrong-way call points at is, or NaN when none has been announced. */
    private static double lastUturnX = Double.NaN;
    private static double lastUturnZ = Double.NaN;
    /** Whether that call was worded for a highway, which is part of its identity. */
    private static boolean lastUturnHighway;
    /** Whether the wrong-way approach line has been spoken for that junction. */
    private static boolean uturnAheadAnnounced;
    /** Whether the wrong-way "now" line has been spoken for it. */
    private static boolean uturnNowAnnounced;
    /** Whether the distance-free U-turn has been spoken for a reading that named no junction. */
    private static boolean uturnPlainAnnounced;
    /**
     * The transit moment being announced: which ride, which stop of it, and whether the two lines a
     * moment can have (the approach and the arrival) have been spoken.
     *
     * <p>Keyed on the ride and the stop rather than on the sentence, because the sentence for a moving
     * vehicle changes with the distance and would otherwise look like news every tick.
     */
    private static int transitRide = Integer.MIN_VALUE;
    private static int transitStop = Integer.MIN_VALUE;
    private static boolean transitAheadAnnounced;
    private static boolean transitNowAnnounced;
    /** Whether the "this is your stop, get off here" line has been said for the ride in force. */
    private static boolean transitArrivedAnnounced;
    /** The road class change count last spoken, so each change is said once and only once. */
    private static int roadClassChanges;
    /** Whether this arrival has already been announced. */
    private static boolean arrivalAnnounced;
    /** Whether the player has already been told they are off route. */
    private static boolean offRouteAnnounced;
    /** Whether the failed-engine line has been logged for the current occurrence. */
    private static boolean unavailableLogged;
    /** Whether the readout should carry the "no speech engine" hint. */
    private static boolean unavailable;
    /** The client tick this update is on, so that a phrase can be held for a length of time. */
    private static int ticks;
    /**
     * The tick the trip's opening line is being left to say itself until, or a tick already past.
     *
     * <p>See {@link #update()}, which says why the opening line is the one phrase nothing is allowed
     * to talk over.
     */
    private static int openingUntil;

    private Narration() {
    }

    /**
     * Speaks one phrase, unless it is the one already being spoken.
     *
     * <p>The single entry point for everything this mod says. Silent when announcements are off, and
     * silent on a repeat of the previous phrase.
     *
     * <p>Hands the phrase to the speaking thread and returns: this is called from the client tick and
     * must never touch the engine. See {@link #speakLoop()}.
     */
    public static void announce(String phrase) {
        try {
            speak(phrase);
        } catch (Throwable t) {
            // Called from the client tick, where an escaping exception takes the game down with it;
            // a phrase that cannot be spoken should cost a log line, not the session.
            HowToGo.LOGGER.error("[HowToGo] could not announce '{}'", phrase, t);
        }
    }

    /**
     * One narration update per client tick.
     *
     * <p>A tick rather than a frame, and driven from the client tick rather than from the HUD
     * renderer: the renderer only runs with nothing open, and announcements have to keep working
     * while the fullscreen map is up.
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

    // ------------------------------------------------------------------ update

    private static void update() {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !RoutePreferenceStore.voiceAnnouncements()) {
            reset();
            return;
        }
        // Started as soon as announcements are on rather than on the first phrase, so the engine's
        // health is known -- and the readout can say so -- before anything has been said. It is a
        // volatile read once the thread exists.
        ensureSpeaker();
        noteEngine();

        Destination target = Navigation.target();
        if (target != session) {
            // A different trip: what was said on the last one must not silence this one. Without
            // this, a second trip to the same place would arrive in total silence, and its first
            // junction would inherit the last trip's latch.
            session = target;
            lastSpoken = null;
            arrivalAnnounced = false;
            offRouteAnnounced = false;
            forgetManeuver();
            resetUturn();
            resetTransit();
            openingUntil = 0;
            // The road class count is the session's, not the notice's: baseline it so a change that
            // happened before this trip began is not announced at its start.
            roadClassChanges = Navigation.classChangeCount();
            if (target != null) {
                // The trip's opening line, and the first thing heard after a destination is picked.
                //
                // Said here rather than where the destination is set, because this branch is the one
                // place that knows a trip has just begun -- and because it has cleared lastSpoken a
                // line earlier, so a trip whose opening words are the same as the last trip's closing
                // ones is still heard. It is not said twice for one trip either: picking the
                // destination already being navigated to is the same trip, leaves the session alone,
                // and has nothing new to announce.
                //
                // The switch is respected without a second test, because everything below the guard at
                // the top of this method is only reached with announcements on.
                String opening = Component.translatable("hud.howtogo.speak_start").getString();
                announce(opening);
                // And then left alone for about as long as it takes to say. This line is a preamble
                // rather than guidance: nothing about it goes out of date, so the one thing that must
                // not happen to it is being talked over -- and the engine would do exactly that. It
                // says a phrase without waiting for it (the Windows voice is handed the text with
                // SPF_ASYNC) and purges whatever is still being said when the next phrase arrives, so
                // a road-surface word or a junction prompt coming one tick later does not queue behind
                // this line, it cuts it off mid-word and the two are heard as one sentence. Reported in
                // play as "问道地图为您导航" and the road notice run together.
                //
                // Nothing below runs while this holds, which is the half that matters: the latches are
                // untouched rather than set, so a junction that comes due inside the hold is announced
                // when it lifts, with the distance it has by then, instead of being swallowed for
                // having been spoken over. The road class count is untouched for the same reason, and
                // its notice therefore follows the opening line as the player asked.
                //
                // Skipped when the engine is known to be dead, where there is nothing to talk over and
                // holding the guidance back would only be silence.
                openingUntil = unavailable ? 0 : ticks + spokenTicks(opening);
            }
        }
        if (target == null) {
            return;
        }
        if (ticks < openingUntil) {
            return;
        }

        // Before the guidance, so that a junction coming up on the same tick ends up as the phrase
        // left in the queue: a change of road surface is worth a word, not worth talking over a turn.
        announceRoadClassChange();

        if (Navigation.showArrival()) {
            if (!arrivalAnnounced) {
                arrivalAnnounced = true;
                // Nothing else is worth saying over the arrival, and the readout has stopped
                // showing turns by this point.
                //
                // The closing line names no destination, deliberately: the banner on screen already
                // says which place this is, and a phrase read out at the end of a trip is heard once,
                // on the move, with nothing to compare it against -- where "the destination" is the
                // one thing the player already knows.
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
            // Cleared rather than latched once and for all: leaving the route a second time is news
            // again, and the sentence would otherwise never be heard twice in one trip.
            offRouteAnnounced = false;
        }

        if (!Navigation.route().isPresent()) {
            resetUturn();
            return;
        }
        // While there is a line to be on -- walking up to the stop of a boarding, or riding one -- what
        // is announced is the transit cue and nothing else: a vehicle does the steering, so its turns
        // are not news. Walking to and from the vehicle falls through to the ordinary guidance below,
        // which is why the wrong-way and turn calls are still reachable on those legs. The off-route
        // warning above still applies throughout: it is about having left the route, not about a
        // junction on it.
        Navigation.TransitStep transit = Navigation.transitStep();
        if (transit != null) {
            announceTransit(transit);
            return;
        }
        resetTransit();
        // The wrong-way call takes precedence, and while it is up the ordinary turns are not
        // announced at all: the route's next turn is not what the player needs to hear while they are
        // travelling away from it.
        Navigation.Uturn uturn = Navigation.wrongWayUturn();
        if (uturn != null) {
            announceUturn(uturn);
            return;
        }
        if (Navigation.wrongWay()) {
            // Going the wrong way with no junction named: the plain U-turn, never the route's next
            // turn, which is what a player travelling away from the route must not be given.
            announceUturnWithoutJunction();
            return;
        }
        // Going the right way again disarms the wrong-way latch, so a second mistake is announced as
        // readily as the first -- the state is not a manoeuvre on the route and has no identity of
        // its own beyond the junction it points at.
        resetUturn();
        announceTurn(Navigation.nextManeuver());
    }

    /**
     * About how long a phrase takes to say, in client ticks.
     *
     * <h2>Why this has to be a guess</h2>
     * The speech library exposes no way to ask. Its whole API is {@code say}, {@code clear},
     * {@code active} and {@code destroy}; on Windows {@code say} hands the text to the SAPI voice with
     * {@code SPF_ASYNC} and returns without waiting, and the only thing that says a phrase is over is
     * the next one arriving -- which carries {@code SPF_PURGEBEFORESPEAK} and cuts it off. There is no
     * queue to inspect and no "is speaking" to read, so a caller that wants a phrase to finish has to
     * allow it the time, and the time has to come from the text.
     *
     * <h2>The text is all there is to go on</h2>
     * A phrase's length is the only thing about it that says anything, and it says different things in
     * different scripts: a Han character is a syllable, a Latin one is a fraction of a word. So the
     * count is per script -- Han, kana and Hangul counted as syllables, everything else as letters --
     * and the two are priced differently. Digits are counted with the letters, which is neither right
     * nor badly wrong: they are read as words, and the voice doing it has a language of its own that
     * this mod cannot see. See the class comment.
     *
     * <p>A floor and a ceiling, because the estimate is used to hold other announcements back: too
     * short and the phrase it is protecting still gets cut, too long and the guidance arrives late for
     * no reason. The floor is also what gives the next phrase its pause, which is what "one after the
     * other" has to sound like rather than two sentences run together.
     *
     * <p>Package-private rather than private so that the harness can check it: this is the only part
     * of the hold that is a decision rather than a clock, and getting it wrong is not visible in the
     * game -- a hold that is too short sounds exactly like the bug it exists to fix.
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
        // Math.clamp is Java 21; 1.20.1 targets Java 17, where the same thing is written this way.
        return ticksFor(Math.max(SPOKEN_MIN_MS, Math.min(SPOKEN_MAX_MS, millis)));
    }

    /** A length of time as client ticks, never less than one. */
    private static int ticksFor(long millis) {
        return Math.max(1, (int) Math.round(millis / MILLIS_PER_TICK));
    }

    /**
     * Says the plain U-turn, for a wrong-way reading that could not name a junction.
     *
     * <p>Once per episode: the latch is disarmed by {@link #resetUturn()} on every tick the call is
     * not up, so a later mistake is spoken again, while a run of ticks with nothing to point at says
     * this once rather than every tick.
     */
    private static void announceUturnWithoutJunction() {
        if (uturnPlainAnnounced) {
            return;
        }
        uturnPlainAnnounced = true;
        // The distance-free form, which is the one that needs no junction: there is nothing to count
        // to here, and naming one anyway would be worse than saying nothing about where.
        announce(Navigation.uturnSentence(Double.NaN, true, Navigation.onHighway()));
    }

    /**
     * Says a word when the road underfoot becomes a different kind of road.
     *
     * <p>An event, not guidance, and kept out of every latch above: it does not raise a manoeuvre,
     * does not touch the turn or U-turn announcements, and cannot move what they point at. It is
     * spoken once per change because the count it watches only moves on a change -- travelling along
     * one road says nothing however long it takes.
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
     * Announces the turn ahead once it is within the lead distance, then again when it is due.
     *
     * <p>The phrase carries the distance, so it changes on every step and cannot be what tells one
     * announcement from the next. The latch is therefore per manoeuvre, not per phrase: a manoeuvre
     * is armed when it becomes the next one, fires the approach line once, fires the "now" line once,
     * and is then quiet until the next manoeuvre takes its place.
     *
     * <p>Manoeuvre identity is its position along the route -- {@code distanceAhead} added back to
     * what has been travelled, which is the same number however far along the approach the player
     * has got -- compared with a block of slack for the rounding in that rebasing. The step count is
     * the only other candidate and is not available: {@link Navigation.Instruction} carries the
     * rebased distance, not the manoeuvre's own.
     */
    private static void announceTurn(Navigation.Instruction maneuver) {
        if (maneuver == null) {
            return;
        }
        double alongRoute = maneuver.distanceAhead() + Navigation.travelled();
        if (!(Math.abs(alongRoute - lastManeuverAt) <= MANEUVER_ID_SLACK)) {
            // A different junction, so both announcements are news again. The comparison is written
            // as a negation so the first manoeuvre of a trip -- where there is nothing to compare
            // against yet -- arms rather than being skipped.
            lastManeuverAt = alongRoute;
            aheadAnnounced = false;
            nowAnnounced = false;
        }

        double distance = maneuver.distanceAhead();
        boolean now = distance <= Navigation.turnNowDistance();
        // Wording comes from Navigation, so the voice and the map readout cannot describe the same
        // turn differently -- including when it is a U-turn, which is worded differently again on a
        // highway.
        if (now) {
            if (!nowAnnounced) {
                nowAnnounced = true;
                // Once the turn is called, the approach line for the same junction is stale even if
                // a re-plan briefly pushes the distance back out past the threshold.
                aheadAnnounced = true;
                announce(Navigation.maneuverSentence(maneuver, true));
            }
            return;
        }
        if (aheadAnnounced || distance > Navigation.turnLeadDistance()) {
            return;
        }
        aheadAnnounced = true;
        announce(Navigation.maneuverSentence(maneuver, false));
    }

    /**
     * Announces the wrong-way call once inside the lead distance, and once more when it is due.
     *
     * <p>Its own latch, because it is not a manoeuvre on the route: there is no position along the
     * route to identify it by, so the junction it points at is the identity. The two are the same
     * thing in practice -- the junction is where the player will turn round -- and comparing it means
     * carrying on past one junction towards the next calls that next one as the new instruction,
     * while a noisy tick that moves nothing does not.
     */
    private static void announceUturn(Navigation.Uturn uturn) {
        double junctionX = uturn.junctionX();
        double junctionZ = uturn.junctionZ();
        if (Math.hypot(junctionX - lastUturnX, junctionZ - lastUturnZ) > MANEUVER_ID_SLACK
                || uturn.highway() != lastUturnHighway) {
            // A different junction, or the same one under a different rule, so both lines are news
            // again. Written as a comparison against the remembered junction rather than against the
            // distance, which changes every step and would re-arm on every one of them.
            lastUturnX = junctionX;
            lastUturnZ = junctionZ;
            lastUturnHighway = uturn.highway();
            uturnAheadAnnounced = false;
            uturnNowAnnounced = false;
            // The same-text guard is re-armed with the latch. The "now" line carries no distance, so
            // it is the same sentence every time: without this, going the wrong way a second time
            // would be swallowed for being the same words in a row, when the first was minutes ago.
            lastSpoken = null;
        }

        double distance = uturn.distanceAhead();
        boolean now = distance <= Navigation.turnNowDistance();
        if (now) {
            if (!uturnNowAnnounced) {
                uturnNowAnnounced = true;
                // The approach line for this junction is stale once the now line has been said, even
                // if the player coasts back out past the threshold.
                uturnAheadAnnounced = true;
                announce(Navigation.uturnSentence(distance, true, uturn.highway()));
            }
            return;
        }
        if (uturnAheadAnnounced || distance > Navigation.turnLeadDistance()) {
            return;
        }
        uturnAheadAnnounced = true;
        announce(Navigation.uturnSentence(distance, false, uturn.highway()));
    }

    /**
     * Disarms the wrong-way latch, so going the wrong way a second time is announced again.
     *
     * <p>Called on every tick the call is not up, which is what makes the re-arming automatic rather
     * than something a correction has to remember to do.
     */
    private static void resetUturn() {
        lastUturnX = Double.NaN;
        lastUturnZ = Double.NaN;
        uturnAheadAnnounced = false;
        uturnNowAnnounced = false;
        uturnPlainAnnounced = false;
    }

    /**
     * Announces the transit moment the guidance is on: the boarding, the stop just reached, or the stop
     * about to be left.
     *
     * <h2>What is said, and when</h2>
     * Three moments, each said once:
     *
     * <ul>
     *   <li><b>Boarding</b>: on arriving at the stop, the line and which way along it, so the rider can
     *       check they are on the right platform for the right direction before the vehicle comes.</li>
     *   <li><b>A stop called at</b>: where the vehicle is now and how many stops are left to the one
     *       being got off at. This is the one that repeats, once per stop, and it is driven by the stop
     *       index rather than by a distance -- a distance would have it said over and over as the
     *       vehicle crawled up to a platform.</li>
     *   <li><b>The stop being got off at</b>: twice -- on the approach, and again standing at it. The
     *       approach is where a rider on a long ride first needs to know what is coming; the stop
     *       itself is where they have to act, and it is the only one of the two that is heard before
     *       the next boarding when the two lines call at the same station and there is no walk to
     *       change on.</li>
     * </ul>
     *
     * <p>Identity is the ride and the stop within it, which is what makes a phrase that changes with
     * the distance -- "still two stops" becoming "still one" -- read as the same moment rather than as
     * news every tick.
     */
    private static void announceTransit(Navigation.TransitStep step) {
        if (step.rideIndex() != transitRide) {
            // A different ride: its stops are news again, and both lines are armed from scratch.
            transitRide = step.rideIndex();
            transitStop = Integer.MIN_VALUE;
            transitAheadAnnounced = false;
            transitNowAnnounced = false;
            transitArrivedAnnounced = false;
        }

        // Standing at the stop this ride is left at: the one moment the player has to act, and the one
        // the guidance used to miss -- the approach line had already been said by the time the vehicle
        // got here. Where the journey changes lines, it names the line being changed onto here as well,
        // because a change at a station both lines call at is acted on right here rather than on a walk
        // to another platform.
        if (step.cue() == Navigation.TransitCue.ARRIVE) {
            if (!transitArrivedAnnounced) {
                transitArrivedAnnounced = true;
                announce(Navigation.transitSentence(step));
            }
            return;
        }

        // Arriving at a stop that is not the one this ride is left at: where we are and what is left.
        // The boarding stop is skipped -- the boarding line has just named it -- and so is the stop
        // being got off at, which the approach line covers.
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
            // Said on the approach, once, and never made truer by saying it again closer in.
            if (transitAheadAnnounced || step.distanceAhead() > Navigation.turnLeadDistance()) {
                return;
            }
            transitAheadAnnounced = true;
            announce(Navigation.transitSentence(step));
        }
    }

    /** Forgets the transit moment being announced, so the next one is armed from scratch. */
    private static void resetTransit() {
        transitRide = Integer.MIN_VALUE;
        transitStop = Integer.MIN_VALUE;
        transitAheadAnnounced = false;
        transitNowAnnounced = false;
        transitArrivedAnnounced = false;
    }

    // ----------------------------------------------------------------- speaking

    /**
     * Hands a phrase to the speaking thread, or drops it.
     *
     * <p>Nanoseconds and no engine call: the tick this runs on is the one that has to stay smooth.
     * The whole method is a string comparison, one lock acquisition and a field write.
     */
    private static void speak(String phrase) {
        if (phrase == null || phrase.isBlank() || !RoutePreferenceStore.voiceAnnouncements()) {
            return;
        }
        if (phrase.equals(lastSpoken)) {
            return;
        }
        // Recorded here rather than after the engine has said it: "the same thing only once in a row"
        // is about what has been queued, and the speaking thread may replace this phrase with a newer
        // one before it gets to it -- which is the same supersession the old synchronous path got
        // from clear().
        lastSpoken = phrase;
        ensureSpeaker();
        synchronized (LOCK) {
            // Newest wins: the slot is overwritten rather than queued behind, so a backlog cannot
            // form and an out-of-date instruction is never spoken after the newer one.
            pending = phrase;
            LOCK.notifyAll();
        }
    }

    /** Starts the speaking thread the first time there is something to say. */
    private static void ensureSpeaker() {
        if (speaker != null) {
            return;
        }
        synchronized (LOCK) {
            if (speaker != null) {
                return;
            }
            Thread thread = new Thread(Narration::speakLoop, "HowToGo narration");
            // A daemon so a stuck voice can never hold the game open at shutdown.
            thread.setDaemon(true);
            speaker = thread;
            thread.start();
        }
    }

    /**
     * The speaking thread's whole life: build the engine once, then say whatever is waiting.
     *
     * <p>The engine is built here rather than on the client thread for two reasons. Its
     * construction is as slow as its use -- it is the same native library -- so it does not belong
     * on the tick either; and on Windows the voice is a COM object, which belongs to the thread that
     * created it. Building and using it on one thread keeps that consistent, where handing a
     * client-thread object to this one would not.
     */
    private static void speakLoop() {
        Narrator narrator = null;
        boolean active = false;
        try {
            // Whatever voice Windows has as its default; the library offers no way to choose one, so
            // the language of this phrase has no say in who reads it. See the class javadoc.
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
                // Debug, and off in normal play: the exact text handed to the engine, so a report of
                // odd pronunciation can be checked against what was actually sent rather than guessed
                // at. Note what it cannot show: which voice read it, since the library cannot say.
                HowToGo.diagnostic("[HowToGo] speaking: {}", phrase);
                // The same sequence the vanilla wrapper uses: drop whatever is being said, then
                // interrupt, which is what a turn prompt wants -- the previous instruction is out of
                // date by then.
                narrator.clear();
                narrator.say(phrase, true);
            } catch (Throwable t) {
                // The loop must not die: a dead speaker is silence with no explanation, and the
                // readout would still be claiming the engine is fine.
                HowToGo.LOGGER.error("[HowToGo] could not speak '{}'", phrase, t);
            }
        }
    }

    /**
     * Waits for the next phrase and takes it.
     *
     * <p>The lock is held only long enough to read and clear the slot -- never while speaking -- so
     * the client thread's enqueue can only ever wait for that, not for the engine.
     *
     * @return the phrase, or null when the thread was interrupted while waiting
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
     * Records whether the engine came up, from the answer the speaking thread published.
     *
     * <p>Reads two volatile flags and never touches the engine, so the client tick and the readout
     * can both ask without blocking on a native call.
     */
    private static void noteEngine() {
        if (!engineKnown) {
            // The speaking thread has not finished building it yet. Say nothing rather than claiming
            // it is broken while it is still starting.
            unavailable = false;
            return;
        }
        unavailable = !engineActive;
        if (!unavailable) {
            // Reset, so a client that loses the engine later is reported again rather than leaving
            // the first warning as the only one the player ever sees.
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
     * Forgets which manoeuvre was being announced, so the next one is armed from scratch.
     *
     * <p>A junction the router re-plans in place does not need this: its position along the route
     * moves, which the identity comparison in {@link #announceTurn} already reads as a different
     * manoeuvre. This is for the cases where there is no position left to compare against -- a new
     * trip, or announcements switched off and on again.
     */
    private static void forgetManeuver() {
        lastManeuverAt = Double.NaN;
        aheadAnnounced = false;
        nowAnnounced = false;
    }

    /** Forgets everything that was said: the state belongs to a trip that is over or switched off. */
    private static void reset() {
        unavailable = false;
        lastSpoken = null;
        session = null;
        arrivalAnnounced = false;
        offRouteAnnounced = false;
        forgetManeuver();
        resetUturn();
        resetTransit();
        // Any hold goes with the trip it was for. A hold that outlived its destination would silence
        // the guidance of the next one for a second or two, for a phrase nobody heard.
        openingUntil = 0;
        // Baseline the class count too: changes that passed while announcements were off are not news
        // when they come back on.
        roadClassChanges = Navigation.classChangeCount();
        // Anything still waiting goes too. The switch is off or there is nobody to speak to, so a
        // phrase handed over a moment ago should not be said now -- which is what switching off
        // mid-phrase did before there was a queue.
        synchronized (LOCK) {
            pending = null;
        }
    }
}
