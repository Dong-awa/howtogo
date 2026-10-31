package bili.dongsz.howtogo.client;

/**
 * The mouse button numbers, as the version being built spells them.
 *
 * <h2>Why 0 is not the left button everywhere</h2>
 * Every hit test in this mod used to compare the button against a literal {@code 0}. That was true
 * for as long as the game read its mouse through GLFW, whose buttons are numbered left 0, right 1,
 * middle 2 -- the whole 1.20.x, 1.21.x and 26.1/26.2 lines.
 *
 * <p>26.3 moved the platform layer to SDL, and SDL numbers the buttons from one:
 * {@code InputConstants.MOUSE_BUTTON_LEFT} is {@code 1} there, {@code MOUSE_BUTTON_MIDDLE} is 2 and
 * {@code MOUSE_BUTTON_RIGHT} is 3. Vanilla followed it -- {@code ContainerEventHandler.mouseClicked}
 * asks for {@code event.button() == 1} on 26.3 where the same method asked for {@code 0} on 26.2.
 *
 * <p>So on 26.3 a click arrives as button 1 and every {@code button == 0} test in this mod answered
 * no: the screens drew their buttons and ignored every press on them, while the game's own widgets
 * kept working because they ask {@code InputConstants} rather than a literal. That is the whole of
 * the "no button responds on 26.3" report, and it is why these numbers live here now.
 *
 * <p>Reading the constant instead of writing the number is what makes one source serve both
 * numbering schemes: no version condition is needed, and no node can drift out of step. The two
 * helpers below are the only button numbers this mod's own hit tests ask about; anything that meets
 * a real event should keep comparing against this, not against a number.
 */
public final class MouseButton {

    /** The left (primary) button: 0 through 26.2, 1 from 26.3. */
    public static final int LEFT = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;

    /** The right (secondary) button: 1 through 26.2, 3 from 26.3. */
    public static final int RIGHT = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT;

    private MouseButton() {
    }
}
