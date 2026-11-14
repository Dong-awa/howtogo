package bili.dongsz.howtogo.client;

/**
 * Mouse button numbers as the version being built spells them.
 * GLFW (through 26.2) numbers left 0, right 1, middle 2; SDL from 26.3 numbers them from one, so
 * MOUSE_BUTTON_LEFT is 1 there. Read InputConstants rather than writing literals: a button == 0
 * hit test silently fails on 26.3.
 */
public final class MouseButton {

    /** The left (primary) button: 0 through 26.2, 1 from 26.3. */
    public static final int LEFT = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;

    /** The right (secondary) button: 1 through 26.2, 3 from 26.3. */
    public static final int RIGHT = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT;

    private MouseButton() {
    }
}
