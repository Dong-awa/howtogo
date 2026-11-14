package bili.dongsz.howtogo.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * One of this mod's own buttons: a rounded face, a centred label, and the action it runs.
 * Drawn rather than built from the vanilla Button so UiTheme roles colour it like the sheet it sits
 * on -- at the cost of keyboard focus, tab order and narration, already true of every other control
 * here.
 * Build in init(), render after the content it belongs over, offer click before the screen handles
 * the click. A button whose action is unavailable is left out of the list, not drawn greyed.
 */
final class PanelButton {

    /** How a button looks; its role in the interface, not the text it carries. */
    enum Kind {
        /** Carries no meaning: cancel, done, open a folder. */
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
     * Hover and press held as motion, so the face slides rather than switching; built once in init(),
     * so a sweep of the cursor across a footer allocates nothing.
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

    boolean hit(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** Runs the action when the click is on the button; reports whether it was. */
    boolean click(double mouseX, double mouseY) {
        if (!hit(mouseX, mouseY)) {
            return false;
        }
        action.run();
        return true;
    }

    /**
     * Draws the face and label under the cursor. The face mixes from its resting colour towards the
     * hover colour over UiMotion.HOVER; the label does not follow, since its colour is picked for
     * contrast. The pixel shrink on press is drawn only -- hit and click use the fixed rectangle.
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
        // Fades with the screen it is drawn on; read rather than passed in, see UiMotion.activeOpacity.
        float shownAt = UiMotion.activeOpacity();
        int face = UiMotion.fade(UiMotion.mix(resting, underCursor, h), shownAt);
        // A pixel face cannot show half a pixel, so the slide decides when the single step happens -- mid
        // ease, not on the frame the cursor arrives.
        int inset = Math.round(press.to(hovered ? 1.0F : 0.0F).eased());
        HudDraw.fillRounded(graphics, x + inset, y + inset, x + width - inset, y + height - inset,
                3, face);
        String shown = font.plainSubstrByWidth(label.getString(), width - 6);
        graphics.drawString(font, shown, x + (width - font.width(shown)) / 2,
                y + (height - 8) / 2, UiMotion.fade(text, shownAt), false);
    }
}
