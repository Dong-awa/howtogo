package bili.dongsz.howtogo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Everything this mod does to the screen stack goes through here: read the current screen,
 * replace it, and delay a swap by one exit animation (~140ms).
 *
 * Read/write used to be a field on the game; 26.2 moved it to minecraft.gui.screen()/
 * setScreen(...). Rather than sprinkle version checks over twenty-odd call sites, both ends
 * go through this class, and reads outnumber writes anyway.
 *
 * A swap waits for UiMotion's exit: set() starts the exiting screen's leave and remembers the
 * target, pump() installs it once that exit finishes or EXIT_TIMEOUT_NANOS elapses. Only
 * screens the mod animates are deferred. A pending swap is dropped if the game changes the
 * screen itself; a second set() while one is pending is swallowed.
 */
public final class Screens {
    /** Upper bound on how long a swap waits for the exit animation before forcing through. */
    private static final long EXIT_TIMEOUT_NANOS = 400_000_000L;
    /** Where the swap is headed; null when the swap is a close. */
    private static Screen pendingTarget;
    private static boolean swapping;
    /** The screen currently playing its exit, or null when no swap is in flight. */
    private static Screen leaving;
    /** System.nanoTime() deadline after which we stop waiting on the animation. */
    private static long deadlineNanos;
    private Screens() { }
    /** The screen showing now, or null. 26.2 moved this onto the GUI layer. */
    public static Screen current() {
        //? if >=26.2 {
        /*return Minecraft.getInstance().gui.screen();
         *///?} else {
        return Minecraft.getInstance().screen;
        //?}
    }

    /**
     * Whether the given screen is the one on display, by identity.
     *
     * Identity, not equals: callers are asking "is the screen I clicked on still up", and a
     * fresh instance of the same class doesn't count.
     */
    public static boolean isCurrent(Screen screen) {
        return screen != null && current() == screen;
    }

    /**
     * Show the given screen, or close the GUI when null. If the screen being replaced belongs
     * to this mod the swap is deferred by one exit animation -- one path covers Escape, footer
     * buttons, imports and commands alike.
     */
    public static void set(Screen screen) {
        if (defer(current(),screen)) {
            return;
        }
        install(screen, false);
    }

    /**
     * True while the interface is on its way out. Exposed for callers that want to answer input
     * differently during a swap; the screens themselves don't need it since a second set is
     * swallowed anyway.
     */
    public static boolean swapping() {
        return swapping;
    }

    /**
     * Per-frame driver for a deferred swap, called by whichever screen is drawing (via UiMotion
     * of). A screen that's still drawing is one that can still finish leaving.
     */
    static void pump() {
        if (!swapping) {
            return;
        }
        if (current()!=leaving) {
            // The game (or another mod) put something else up during the exit -- drop the swap
            // rather than install over it.
            clear();
            return;
        }
        if (System.nanoTime()<deadlineNanos&&!UiMotion.left(leaving)) {
            return;
        }

        Screen target = pendingTarget;
        Screen gone = leaving;
        // Read before clear(): the incoming page has to continue the transition the outgoing
        // one started, or the hand-off turns into two separate fades.
        boolean handoff = UiMotion.leavingAsHandoff(gone);
        clear();
        install(target,handoff);
    }

    /** Forget the in-flight swap, whichever end it reached. */
    private static void clear() {
        swapping = false;
        pendingTarget = null;
        leaving = null;
    }

    /**
     * Whether the screen belongs to this mod. Checked by package name rather than a marker so
     * screens added later need no list to keep in step; a mod screen living outside
     * bili.dongsz.howtogo.* would be missed.
     */
    private static boolean isModScreen(Screen screen) {
        return screen != null && screen.getClass().getName().startsWith("bili.dongsz.howtogo.");
    }

    private static boolean defer(Screen current, Screen target) {
        if (swapping) {
            if (current==leaving && System.nanoTime()<deadlineNanos) {
                return true;
            }
            clear();
        }
        if (!UiMotion.on() || current==null || current==target) {
            return false;
        }
        // A dialog isn't a page: it draws over its parent and re-renders it underneath, so the
        // parent never actually moves. Fading it out on open would make the dialog's first
        // frame flash the whole UI; fading it in on close would fade something that never left.
        if (current instanceof ParentBackdrop || target instanceof ParentBackdrop) {
            return false;
        }
        // Two of our own pages back-to-back is a hand-off: the outgoing content fades to
        // HANDOFF_FLOOR while its sheet stays up, and the incoming content picks the fade up
        // from there on the same sheet. A full exit first would flash the world between pages.
        //
        // Closing to the world (null target) keeps the full exit -- the surface really is going
        // away.
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
     * Install a screen at whatever point of its transition it belongs at.
     *
     * Other screens' state is deliberately left alone: parents get reused as instances (a
     * picker holds the main screen and returns to that same object), and two of our screens
     * draw their parent underneath, so a parent whose state was dropped would fade in again
     * behind its own child. UiMotion.adopt decides between continuing a hand-off, revealing a
     * page that never left, and opening one never drawn.
     *
     * The replaced screen is not settled here: nothing draws it anymore (only dialogs draw
     * another screen, and dialog swaps are never deferred), and settling it produced one frame
     * of a fully opaque screen that had just faded to nothing -- the second blink on a panel's
     * last button.
     *
     * Only screens the mod animates get adopted: an exit can only finish if the screen calls
     * UiMotion.of, so handing a screen back to the game without ever drawing it must not be
     * recorded as waitable -- that left Xaero's map swallowing later sets until it was closed.
     * A screen with no state loses nothing; its first UiMotion.of frame opens it.
     */
    private static void install(Screen screen, boolean handoff) {
        UiMotion.adopt(screen, handoff);
        //? if >=26.2 {
        /*Minecraft.getInstance().gui.setScreen(screen);
         *///?} else {
        Minecraft.getInstance().setScreen(screen);
        //?}
    }

    /** The current screen as mc.screen would read, for callers already holding the game. */
    public static Screen of(Minecraft minecraft) {
        //? if >=26.2 {
        /*return minecraft.gui.screen();
         *///?} else {
        return minecraft.screen;
        //?}
    }
}