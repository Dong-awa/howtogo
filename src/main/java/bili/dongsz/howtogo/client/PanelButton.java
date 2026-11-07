package bili.dongsz.howtogo.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * One of this mod's own buttons: a rounded face, a centred label, and the action it runs.
 *
 * <h2>Why these are drawn rather than built</h2>
 * A vanilla {@link net.minecraft.client.gui.components.Button} draws itself in the game's grey
 * widget palette, which is not this mod's palette in either style -- a row of grey system buttons
 * along the bottom of a white sheet is the one thing in the interface that does not belong to the
 * theme, and it was the loudest such thing left. Drawing the face here instead means the same
 * {@link UiTheme} roles colour the buttons as colour the sheets they sit on: a neutral button is the
 * theme's button fill, the main action is the accent, a destructive one is the danger colour, and the
 * label follows the face.
 *
 * <p>The cost is what a real widget does that this does not: keyboard focus, the tab order, and the
 * narration that reads a focused widget aloud. That is a deliberate trade and it is already the
 * shape of the rest of this mod's controls -- the destination picker's footer, its mode row and every
 * toggle in these screens are drawn and hit-tested by hand for the same reason -- so a screen here
 * has one kind of button to keep right, not two.
 *
 * <h2>What a screen does with one</h2>
 * Build the list in {@code init()} (it is where the geometry is worked out), call
 * {@link #render} from the screen's own {@code render} after the content it belongs over, and offer
 * {@link #click} from {@code mouseClicked} before the screen handles the click itself. A button whose
 * action is not available leaves itself out of the list rather than being drawn greyed: a control the
 * player cannot use is better absent than present and dead.
 */
final class PanelButton {

    /** How a button looks, which is what it does in the interface rather than what it says. */
    enum Kind {
        /** A button that carries no meaning by itself: cancel, done, open a folder. */
        NEUTRAL,
        /** The action the screen exists for: go here, start, confirm. */
        PRIMARY,
        /** An action that destroys or overwrites something. */
        DANGER
    }

    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final Component label;
    private final Kind kind;
    private final Runnable action;

    /*
     * The button's two states, held as motion rather than as booleans. The face is the one control in
     * the mod that every screen draws, so a hover that switched instantly here would be the one
     * instant change left in an otherwise eased interface. Both slides live on the button -- a button
     * is built once in init() and drawn every frame -- so a sweep of the cursor across a footer costs
     * no allocation.
     */
    private final UiMotion.Slide hover =
            new UiMotion.Slide(UiMotion.Easing.OUT_CUBIC, 0.0F, UiMotion.HOVER);
    private final UiMotion.Slide press =
            new UiMotion.Slide(UiMotion.Easing.OUT_QUAD, 0.0F, UiMotion.PRESS);

    PanelButton(int x, int y, int width, int height, Component label, Kind kind, Runnable action) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.label = label;
        this.kind = kind;
        this.action = action;
    }

    /** Whether the point is on this button, which is the whole of its hit test. */
    boolean hit(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** Runs the action when the click is on this button; reports whether it was. */
    boolean click(double mouseX, double mouseY) {
        if (!hit(mouseX, mouseY)) {
            return false;
        }
        action.run();
        return true;
    }

    /**
     * The face and the label, under the cursor when it is there.
     *
     * <p>The two faces each kind owns are still read from {@link UiTheme}; what changes is that the
     * one under the cursor is no longer picked between them but mixed towards, so the face spends
     * {@link UiMotion#HOVER} travelling instead of switching. The label deliberately does not follow
     * that mix: the label colour is chosen for contrast against the resting face, and interpolating it
     * would put the text through values nobody picked for legibility. The face shrinking by a pixel
     * while the cursor is on it is the whole of the press, and it is drawn only -- {@link #hit} and
     * {@link #click} still answer from the button's own rectangle, so the clickable area never moves.
     */
    void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        boolean hovered = hit(mouseX, mouseY);
        float h = hover.to(hovered ? 1.0F : 0.0F).eased();
        int resting;
        int underCursor;
        int text;
        switch (kind) {
            case PRIMARY -> {
                resting = UiTheme.accent();
                underCursor = UiTheme.accentHover();
                text = UiTheme.onSelection();
            }
            case DANGER -> {
                resting = UiTheme.danger();
                underCursor = UiTheme.dangerHover();
                text = UiTheme.onSelection();
            }
            default -> {
                resting = UiTheme.button();
                underCursor = UiTheme.buttonHover();
                text = UiTheme.onButton();
            }
        }
        // The screen the button is drawn on may itself be arriving or leaving; a button is not a
        // separate thing from its sheet, so it fades with it. The opacity is read rather than passed
        // in, which is why no call site had to change -- see UiMotion#activeOpacity.
        float shownAt = UiMotion.activeOpacity();
        int face = UiMotion.fade(UiMotion.mix(resting, underCursor, h), shownAt);
        // A face of pixels cannot show half a pixel of travel, so the slide decides when the single
        // step happens rather than how far it goes: the middle of a 90 ms ease, not the frame the
        // cursor arrives on. This is an affordance for the cursor being on the button -- the game's
        // own held-down state is not readable here without a new API call, and a press that cannot
        // be seen while it happens is worse than a hover that reads as one.
        int inset = Math.round(press.to(hovered ? 1.0F : 0.0F).eased());
        HudDraw.fillRounded(graphics, x + inset, y + inset, x + width - inset, y + height - inset,
                3, face);
        String shown = font.plainSubstrByWidth(label.getString(), width - 6);
        graphics.drawString(font, shown, x + (width - font.width(shown)) / 2,
                y + (height - 8) / 2, UiMotion.fade(text, shownAt), false);
    }
}
