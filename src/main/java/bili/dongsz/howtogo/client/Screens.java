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
        install(screen);
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
        clear();
        // Settle rather than drop: a screen the new one draws as its backdrop has to be static behind
        // it, and a dropped state would make that backdrop play an entrance of its own.
        UiMotion.release(gone);
        install(target);
    }

    /** Forgets the swap in flight, whichever end it reached. */
    private static void clear() {
        swapping = false;
        pendingTarget = null;
        leaving = null;
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
        // A screen that re-renders its parent underneath itself is a dialog over it, not a
        // replacement for it: the parent is still on screen, at full strength. There is nothing to
        // fade out, and fading it would be undone one frame later when the dialog redrew it -- which
        // reads as the whole big interface flashing on its way to the dialog. So the dialog is
        // installed at once and the parent never leaves. See {@link ParentBackdrop}.
        if (target instanceof ParentBackdrop) {
            return false;
        }
        if (!UiMotion.beginLeave(current)) {
            return false;
        }
        swapping = true;
        pendingTarget = target;
        leaving = current;
        deadlineNanos = System.nanoTime() + EXIT_TIMEOUT_NANOS;
        return true;
    }

    /**
     * Installs a screen, at the start of its entrance.
     *
     * <p>The state of every other screen is deliberately left alone. Screen instances are reused as
     * parents -- a picker holds the main screen and returns to that same object -- and two of the
     * mod's screens draw their parent underneath themselves, so a parent whose state was dropped
     * here would fade in again behind its own child. Reopening is enough: an instance that is shown
     * a second time gets its entrance, and one that was merely drawn underneath keeps the state it
     * already had. States of screens that are genuinely gone are collected with the screens.
     *
     * <h2>Only a screen the mod animates is adopted</h2>
     * Reopening <em>creates</em> a screen's animation state, and a screen's animation state is what
     * makes {@link #defer} willing to wait for its exit -- an exit that only a screen calling
     * {@link UiMotion#of} can ever finish, because that is the pump. So a screen that is merely
     * handed back to the game without the mod ever drawing it must not be adopted here: it would be
     * recorded as something whose exit can be waited for, and every later request to leave it would
     * be deferred to a frame that never comes. That is exactly what returning from one of the mod's
     * panels to Xaero's world map looked like -- the map was reopened, adopted, and from then on
     * pressing N or L anywhere in it did nothing at all until the map was closed and a fresh map
     * screen (a different instance, so not adopted) was opened.
     *
     * <p>A screen that is not animated yet loses nothing by this test: its first frame of
     * {@link UiMotion#of} is what opens it, and a screen arriving for the first time is opened from
     * the start, which is the same entrance it would have been given here. A screen that was drawn as
     * the backdrop of the one it replaces is revealed as it stands rather than played in again; see
     * {@link UiMotion#adopt}.
     */
    private static void install(Screen screen) {
        UiMotion.adopt(screen);
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
