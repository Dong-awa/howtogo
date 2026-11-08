package bili.dongsz.howtogo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The three things this mod does to the game's screen stack, in the shape each node wants: read the
 * screen, replace it, and -- since the interface animates -- swap it a hundred and forty milliseconds
 * after the player asked for the swap rather than in the same call.
 *
 * <h2>Why the shapes differ</h2>
 * Reading and replacing the current screen used to be a field on the game -- {@code minecraft.screen},
 * which the mod wrote and replaced directly, and which {@code setScreen} set. From 26.2 both moved
 * onto the game's GUI layer, where they are a getter and a setter: {@code minecraft.gui.screen()} and
 * {@code minecraft.gui.setScreen(...)}. The GUI layer is the right home for them: whether a screen is
 * showing and what replaces it is the GUI's business, not the game loop's.
 *
 * <p>Both are asked through here rather than through a condition at each of the twenty-odd call
 * sites. Three reasons, in order of how much they cost to learn:
 *
 * <ul>
 *   <li>A screen swap is not a rename that reads well either way. The newer form has an extra
 *       receiver, so a condition at the call site is two lines where one will do, twenty times.
 *   <li>The tests are the interesting part and there are more of them than the writes -- most uses
 *       ask "is one of my screens up?" or "is the screen still the one I pressed the button on?".
 *       Written once here, the answer is the same question on every node.
 *   <li>The replacement rules in the build cannot help: a rule matching {@code .screen} would also
 *       match every other field of that name, and {@code .setScreen(} would match the game's own
 *       method and this file's.
 * </ul>
 *
 * <h2>Why a swap waits for an animation</h2>
 * {@link UiMotion} knows how a screen leaves, but only the stack knows when. So {@link #set} starts the
 * leaving screen's exit and records what it was asked to show; the frame in which that exit finishes
 * -- or {@link #EXIT_TIMEOUT_NANOS} later, whichever comes first -- {@link #pump} installs it. The
 * outgoing screen keeps drawing for those hundred and forty milliseconds, which is the whole point:
 * a screen that vanished on the click would have no exit to show.
 *
 * <p>Three rules keep that deferral from being a way to break navigation:
 *
 * <ul>
 *   <li>Only a screen the mod is animating is deferred -- see {@link UiMotion#tracked}, which is true
 *       of the mod's own screens and of nothing else. A vanilla screen is replaced at once, because
 *       nothing would draw its exit anyway.</li>
 *   <li>If the game replaces the screen itself while a swap is pending -- a disconnect, a crash
 *       report, another mod -- the pending swap is dropped rather than installed over whatever the
 *       game decided to show. That is the one case in which a deferred swap could resurrect a screen,
 *       so it is checked every frame rather than trusted.</li>
 *   <li>A second {@link #set} while one is pending is swallowed, so a click during the exit cannot
 *       queue a second screen behind the first.</li>
 * </ul>
 */
public final class Screens {

    /** How long the swap waits for an exit animation before installing regardless. */
    private static final long EXIT_TIMEOUT_NANOS = 400_000_000L;

    /** The screen waiting for the current one to finish leaving; null when the swap closes the GUI. */
    private static Screen pendingTarget;
    /**
     * Whether a swap is in flight.
     *
     * <p>A flag of its own, and this is worth being blunt about: the first version of this file used
     * {@code pendingTarget != null} as the flag, and closing a screen records a null target, so
     * closing the main screen started the exit and then never performed the swap. The screen stayed
     * installed at zero opacity with the world visible through it, still taking every keystroke --
     * a frozen game behind an invisible window. "Is a swap pending" and "what is it pending on" are
     * two questions, and a null answer to the second one is a perfectly good answer.
     */
    private static boolean swapping;
    /** The screen that is drawing its exit right now, or null when no swap is in flight. */
    private static Screen leaving;
    /** When the swap stops waiting for the animation, in {@link System#nanoTime()} terms. */
    private static long deadlineNanos;

    private Screens() {
    }

    /**
     * The screen showing now, or null if none is.
     *
     * <p>26.2 moved this onto the GUI layer; before that it was a field on the game.
     */
    public static Screen current() {
        //? if >=26.2 {
        /*return Minecraft.getInstance().gui.screen();
        *///?} else {
        return Minecraft.getInstance().screen;
        //?}
    }

    /**
     * Whether the screen showing now is that exact screen.
     *
     * <p>Identity rather than equality, and deliberately: the callers ask whether the screen they
     * pressed a button on is still up, and a new screen of the same class is not that screen.
     */
    public static boolean isCurrent(Screen screen) {
        return screen != null && current() == screen;
    }

    /**
     * Replaces the showing screen, or closes it when given null.
     *
     * <p>The replacement is deferred by one exit animation when the screen being replaced is one of
     * the mod's own; see the class note. Every call site in the mod goes through here, which is what
     * makes that one animation cover every way out of every screen -- a footer button, Escape, the
     * end of an import, a command.
     */
    public static void set(Screen screen) {
        if (defer(current(), screen)) {
            return;
        }
        install(screen, false);
    }

    /**
     * Whether a swap is in flight, which is the window in which the interface is on its way out.
     *
     * <p>For a caller that has to answer input differently while the screen is leaving; the screens
     * themselves do not need it, because a second {@link #set} is swallowed anyway.
     */
    public static boolean swapping() {
        return swapping;
    }

    /**
     * The frame's pump for a deferred swap.
     *
     * <p>Called once per frame by the screen that is drawing -- {@link UiMotion#of} is where, since
     * every screen asks it for its own animation state. A screen that is still drawing is a screen
     * that can still finish leaving, so the pump belongs to exactly the code that knows that.
     */
    static void pump() {
        if (!swapping) {
            return;
        }
        if (current() != leaving) {
            // The game (or another mod) put something else on screen during the exit: drop the swap
            // rather than install a screen over it.
            clear();
            return;
        }
        if (System.nanoTime() < deadlineNanos && !UiMotion.left(leaving)) {
            return;
        }
        Screen target = pendingTarget;
        Screen gone = leaving;
        // Read before the record is cleared: the page being installed has to continue the transition
        // the page leaving started. A hand-off whose two halves were decided separately is two fades.
        boolean handoff = UiMotion.leavingAsHandoff(gone);
        clear();
        install(target, handoff);
    }

    /** Forgets the swap in flight, whichever end it reached. */
    private static void clear() {
        swapping = false;
        pendingTarget = null;
        leaving = null;
    }

    /**
     * Whether the screen is one of this mod's own.
     *
     * <p>Asked by package name rather than through a marker on every screen class: the question is
     * only ever "is this one of ours", and every screen of the mod lives under the mod's package, so
     * a screen added later is answered by the same test with no list to keep in step. The cost is
     * that a screen of ours placed in another package would be missed -- which is the same cost a
     * marker has when someone forgets to declare it, and one less line to forget.
     */
    private static boolean isModScreen(Screen screen) {
        return screen != null && screen.getClass().getName().startsWith("bili.dongsz.howtogo.");
    }

    /**
     * Starts the exit animation when the swap is one that can be animated, and records the target.
     *
     * <p>Answers whether the caller should skip the swap: true when the exit was started, or when one
     * was already in flight and this request is dropped.
     *
     * <p>A pending swap is only allowed to swallow a request while it can still happen -- while the
     * leaving screen is still the one on show and the deadline has not passed. Without that test a
     * single missed pump would leave a swap recorded forever and every later {@link #set} swallowed
     * with it, which is the difference between one animation not finishing and the mod's navigation
     * being dead until the game is restarted. A stale record is dropped instead, and the request in
     * hand goes through unanimated.
     */
    private static boolean defer(Screen current, Screen target) {
        if (swapping) {
            if (current == leaving && System.nanoTime() < deadlineNanos) {
                return true;
            }
            clear();
        }
        if (!UiMotion.on() || current == null || current == target) {
            return false;
        }
        // A dialog is not a page. It is drawn <em>over</em> the screen it belongs to -- it re-renders
        // its parent underneath itself, see {@link ParentBackdrop} -- so in both directions the page
        // underneath stays exactly where it is: opening the dialog must not fade the parent out (the
        // dialog would redraw it a frame later, which reads as the whole big interface flashing), and
        // closing it must not fade the parent in (it was never gone). The dialog itself simply appears
        // and disappears over a surface that never moved.
        if (current instanceof ParentBackdrop || target instanceof ParentBackdrop) {
            return false;
        }
        // Two of the mod's own pages, one after the other. This is a hand-off rather than a
        // disappearance: the outgoing page's content fades to {@link UiMotion#HANDOFF_FLOOR} while its
        // sheet stays up, and the incoming page's content picks the fade up from the same floor on the
        // same sheet. Playing the old page's full exit first would take the surface down with it, and
        // for a frame or two the player would be looking at the world between two pages -- which is
        // exactly the flash that a page change should never have.
        //
        // Closing to the world (a null target) keeps the full exit: there the surface really is going
        // away, and the fade is the only thing that says so.
        boolean handoff = isModScreen(target);
        if (!UiMotion.beginLeave(current, handoff)) {
            return false;
        }
        swapping = true;
        pendingTarget = target;
        leaving = current;
        deadlineNanos = System.nanoTime() + EXIT_TIMEOUT_NANOS;
        return true;
    }

    /**
     * Installs a screen, at whatever point of its transition it belongs at.
     *
     * <p>The state of every other screen is deliberately left alone. Screen instances are reused as
     * parents -- a picker holds the main screen and returns to that same object -- and two of the
     * mod's screens draw their parent underneath themselves, so a parent whose state was dropped
     * here would fade in again behind its own child; see {@link UiMotion#adopt}, which is what
     * decides between continuing a hand-off, revealing a page that never left, and opening a page
     * that has never been drawn.
     *
     * <h2>Why the outgoing screen is not settled here</h2>
     * It used to be: the screen being replaced was put back at full opacity as it was installed over,
     * on the grounds that a screen drawn as a backdrop has to be static. But nothing draws the
     * replaced screen any more -- the only screens that draw another screen under themselves are the
     * dialogs, and a dialog swap is never deferred in the first place (see {@link #defer}) -- so the
     * only thing that settle achieved was one frame of a fully opaque screen that had just faded to
     * nothing, immediately before the game took it away. That is the second of the two blinks a player
     * saw when pressing the last button of a panel.
     *
     * <h2>Only a screen the mod animates is adopted</h2>
     * A screen's animation state is what makes {@link #defer} willing to wait for its exit -- an exit
     * that only a screen calling {@link UiMotion#of} can ever finish, because that is the pump. So a
     * screen that is merely handed back to the game without the mod ever drawing it must not be
     * adopted: it would be recorded as something whose exit can be waited for, and every later request
     * to leave it would be deferred to a frame that never comes. That is exactly what returning from
     * one of the mod's panels to Xaero's world map looked like -- the map was adopted, and from then on
     * pressing N or L anywhere in it did nothing at all until the map was closed and a fresh map
     * screen (a different instance, so not adopted) was opened. A screen with no state loses nothing by
     * this: its first frame of {@link UiMotion#of} is what opens it, and that is the same entrance it
     * would have been given here.
     */
    private static void install(Screen screen, boolean handoff) {
        UiMotion.adopt(screen, handoff);
        //? if >=26.2 {
        /*Minecraft.getInstance().gui.setScreen(screen);
        *///?} else {
        Minecraft.getInstance().setScreen(screen);
        //?}
    }

    /** The screen as {@code mc.screen} would read, for callers holding the game already. */
    public static Screen of(Minecraft minecraft) {
        //? if >=26.2 {
        /*return minecraft.gui.screen();
        *///?} else {
        return minecraft.screen;
        //?}
    }
}
