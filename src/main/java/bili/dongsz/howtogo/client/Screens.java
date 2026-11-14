package bili.dongsz.howtogo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The three things this mod does to the game's screen stack: read the screen, replace it, and delay a swap by one exit animation (~140 ms).
 * Reading and replacing moved from a game field to minecraft.gui.screen()/setScreen(...) in 26.2; both are asked through here rather than a condition at each of the twenty-odd call sites, and the tests ask more often than the writes.
 * A swap waits for UiMotion's exit: set starts the leaving screen's exit and records the target, and pump installs it when that exit finishes or EXIT_TIMEOUT_NANOS later. Only screens the mod animates (UiMotion tracked) are deferred; a pending swap is dropped if the game replaces the screen itself, and a second set while one is pending is swallowed.
 */
public final class Screens {

    /** How long the swap waits for an exit animation before installing regardless. */
    private static final long EXIT_TIMEOUT_NANOS = 400_000_000L;

    /** The screen waiting for the current one to finish leaving; null when the swap closes the GUI. */
    private static Screen pendingTarget;
    /**
     * Whether a swap is in flight.
     * A flag of its own, not pendingTarget != null: closing records a null target, and using the target as the flag left the screen installed at zero opacity, taking every keystroke -- a frozen game behind an invisible window.
     */
    private static boolean swapping;
    /** The screen that is drawing its exit right now, or null when no swap is in flight. */
    private static Screen leaving;
    /** When the swap stops waiting for the animation, in System nanoTime terms. */
    private static long deadlineNanos;

    private Screens() {
    }

    /**
     * The screen showing now, or null if none is. 26.2 moved this onto the GUI layer; before that it was a field on the game.
     */
    public static Screen current() {
        //? if >=26.2 {
        /*return Minecraft.getInstance().gui.screen();
        *///?} else {
        return Minecraft.getInstance().screen;
        //?}
    }

    /**
     * Whether the screen showing now is that exact screen. Identity rather than equality: callers ask whether the screen they pressed a button on is still up, and a new screen of the same class is not that screen.
     */
    public static boolean isCurrent(Screen screen) {
        return screen != null && current() == screen;
    }

    /**
     * Replaces the showing screen, or closes it when given null. The replacement is deferred by one exit animation when the screen being replaced is one of the mod's own; every call site goes through here, so that one animation covers Escape, footer buttons, imports and commands.
     */
    public static void set(Screen screen) {
        if (defer(current(), screen)) {
            return;
        }
        install(screen, false);
    }

    /**
     * Whether a swap is in flight, the window in which the interface is on its way out. For a caller that answers input differently while the screen leaves; the screens do not need it, because a second set is swallowed anyway.
     */
    public static boolean swapping() {
        return swapping;
    }

    /**
     * The frame's pump for a deferred swap. Called once per frame by the screen that is drawing (UiMotion of), since a screen that is still drawing is a screen that can still finish leaving.
     */
    static void pump() {
        if (!swapping) {
            return;
        }
        if (current() != leaving) {
            // The game (or another mod) put something else on screen during the exit: drop the swap rather than install a screen over it.
            clear();
            return;
        }
        if (System.nanoTime() < deadlineNanos && !UiMotion.left(leaving)) {
            return;
        }
        Screen target = pendingTarget;
        Screen gone = leaving;
        // Read before the record is cleared: the page being installed has to continue the transition the leaving page started, or the hand-off becomes two fades.
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
     * Whether the screen is one of this mod's own. Asked by package name rather than a marker, so a screen added later needs no list to keep in step; a screen of ours in another package would be missed.
     */
    private static boolean isModScreen(Screen screen) {
        return screen != null && screen.getClass().getName().startsWith("bili.dongsz.howtogo.");
    }

    /**
     * Starts the exit animation when the swap can be animated, and records the target.
     * Answers whether the caller should skip the swap: true when the exit was started, or when one was already in flight and this request is dropped.
     * A pending swap may only swallow a request while it can still happen -- the leaving screen is still on show and the deadline has not passed. Otherwise a single missed pump would swallow every later set, killing navigation until restart.
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
        // A dialog is not a page: it draws over the screen it belongs to, re-rendering its parent underneath, so in both directions the page underneath stays where it is. Opening must not fade the parent out (the dialog would redraw it a frame later, flashing the whole interface) and closing must not fade it in (it never left).
        if (current instanceof ParentBackdrop || target instanceof ParentBackdrop) {
            return false;
        }
        // Two of the mod's own pages, one after the other: a hand-off. The outgoing content fades to HANDOFF_FLOOR while its sheet stays up, and the incoming content picks the fade up from there on the same sheet. Playing the old page's full exit first would show the world between two pages -- the flash a page change must never have.
        // Closing to the world (a null target) keeps the full exit, since the surface really is going away.
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
     * Every other screen's state is deliberately left alone: screen instances are reused as parents -- a picker holds the main screen and returns to that same object -- and two of the mod's screens draw their parent underneath themselves, so a parent whose state was dropped would fade in again behind its own child. UiMotion adopt decides between continuing a hand-off, revealing a page that never left, and opening one never drawn.
     * The replaced screen is not settled here: nothing draws it any more (only dialogs draw another screen, and a dialog swap is never deferred), and settling it produced one frame of a fully opaque screen that had just faded to nothing -- the second blink on the last button of a panel.
     * Only a screen the mod animates is adopted: its exit can only finish if it calls UiMotion of, so a screen handed back to the game without the mod ever drawing it must not be recorded as something whose exit can be waited for -- that left Xaero's map swallowing every later set until it was closed. A screen with no state loses nothing: its first UiMotion of frame opens it.
     */
    private static void install(Screen screen, boolean handoff) {
        UiMotion.adopt(screen, handoff);
        //? if >=26.2 {
        /*Minecraft.getInstance().gui.setScreen(screen);
        *///?} else {
        Minecraft.getInstance().setScreen(screen);
        //?}
    }

    /** The screen as mc.screen would read, for callers holding the game already. */
    public static Screen of(Minecraft minecraft) {
        //? if >=26.2 {
        /*return minecraft.gui.screen();
        *///?} else {
        return minecraft.screen;
        //?}
    }
}
