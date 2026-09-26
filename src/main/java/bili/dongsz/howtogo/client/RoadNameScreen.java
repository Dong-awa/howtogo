package bili.dongsz.howtogo.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.Consumer;

/**
 * Compact inline prompt for naming a road.
 *
 * <p>Deliberately not a full screen. The map is re-rendered underneath and only a small panel is
 * drawn, anchored next to the point the player was working on, so naming does not interrupt
 * navigation and the surroundings stay readable.
 *
 * <p>A real {@link EditBox} is used rather than capturing raw key codes on the map, because that
 * is the only way to get the platform input method working for non-ASCII names.
 */
public final class RoadNameScreen extends Screen {

    public static final String TITLE_ROAD = "screen.howtogo.name_road";
    public static final String TITLE_POI = "screen.howtogo.name_poi";
    /**
     * Naming an automatically detected railway.
     *
     * <p>Its own title rather than the road one, because it is also the confirmation that the click
     * landed on the rail layer: the two lines are drawn in the same place by the same code, and a
     * player who cannot tell which one they selected would not know what they are naming.
     */
    public static final String TITLE_RAIL = "screen.howtogo.name_rail";

    private static final int FIELD_WIDTH = 190;
    private static final int FIELD_HEIGHT = 18;
    private static final int PANEL_PAD = 5;
    private static final int TITLE_HEIGHT = 12;
    private static final int BUTTON_HEIGHT = 16;

    private final Screen parent;
    private final String titleKey;
    private final String initialValue;
    private final double anchorX;
    private final double anchorY;
    private final Consumer<String> onAccept;

    private EditBox field;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public RoadNameScreen(Screen parent, String titleKey, String initialValue,
                          double anchorX, double anchorY, Consumer<String> onAccept) {
        super(Component.translatable(titleKey));
        this.parent = parent;
        this.titleKey = titleKey;
        this.initialValue = initialValue;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.onAccept = onAccept;
    }

    @Override
    protected void init() {
        // Sit just to the right of the anchor, then keep the panel fully on screen.
        int fieldX = Mth.clamp((int) Math.round(anchorX) + 14, 4, Math.max(4, width - FIELD_WIDTH - 4));
        int fieldY = Mth.clamp((int) Math.round(anchorY) - FIELD_HEIGHT / 2, 4,
                Math.max(4, height - FIELD_HEIGHT - BUTTON_HEIGHT - 14));

        panelW = FIELD_WIDTH + PANEL_PAD * 2;
        panelH = TITLE_HEIGHT + FIELD_HEIGHT + BUTTON_HEIGHT + PANEL_PAD * 3;
        panelX = fieldX - PANEL_PAD;
        panelY = fieldY - PANEL_PAD - TITLE_HEIGHT;

        field = new EditBox(this.font, fieldX, fieldY, FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable(titleKey));
        field.setMaxLength(48);
        field.setValue(initialValue == null ? "" : initialValue);
        addRenderableWidget(field);
        setInitialFocus(field);

        int buttonY = fieldY + FIELD_HEIGHT + PANEL_PAD;
        int half = (FIELD_WIDTH - PANEL_PAD) / 2;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> accept())
                .bounds(fieldX, buttonY, half, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(fieldX + half + PANEL_PAD, buttonY, half, BUTTON_HEIGHT).build());
    }

    private void accept() {
        onAccept.accept(field.getValue());
        onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter confirms, so a name can be typed and committed without reaching for the mouse.
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            accept();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** No dim, no blur: the map underneath is the context for the name being typed. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (parent != null) {
            try {
                parent.render(graphics, mouseX, mouseY, partialTick);
            } catch (Throwable t) {
                // The map is only backdrop here; if it cannot be re-rendered we still want the
                // prompt to be usable.
            }
        }

        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, 0xFF000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xE0181818);
        graphics.drawString(this.font, this.title, panelX + PANEL_PAD, panelY + PANEL_PAD,
                0xFFFFE070, false);

        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
