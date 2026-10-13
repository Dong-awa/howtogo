package bili.dongsz.howtogo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The two things this mod does to the game's screen stack, in the shape each node wants.
 *
 * <h2>Why the shapes differ</h2>
 * Reading and replacing the current screen used to be a field on the game -- {@code minecraft.screen},
 * which the mod wrote and replaced directly, and which {@code setScreen} set. From 26.1 both moved
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
 */
public final class Screens {

    private Screens() {
    }

    /**
     * The screen showing now, or null if none is.
     *
     * <p>26.1 moved this onto the GUI layer; before that it was a field on the game.
     */
    public static Screen current() {
        //? if >=26.1 {
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

    /** Replaces the showing screen, or closes it when given null. */
    public static void set(Screen screen) {
        //? if >=26.1 {
        /*Minecraft.getInstance().gui.setScreen(screen);
        *///?} else {
        Minecraft.getInstance().setScreen(screen);
        //?}
    }

    /** The screen as {@code mc.screen} would read, for callers holding the game already. */
    public static Screen of(Minecraft minecraft) {
        //? if >=26.1 {
        /*return minecraft.gui.screen();
        *///?} else {
        return minecraft.screen;
        //?}
    }
}
