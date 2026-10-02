package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.PlaceKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Compact inline prompt for naming a road, or for editing a place.
 *
 * <p>Deliberately not a full screen. The map is re-rendered underneath and only a small panel is
 * drawn, anchored next to the point the player was working on, so naming does not interrupt
 * navigation and the surroundings stay readable.
 *
 * <p>A real {@link EditBox} is used rather than capturing raw key codes on the map, because that
 * is the only way to get the platform input method working for non-ASCII names.
 *
 * <h2>Why the type row is optional rather than a second screen</h2>
 * A place is edited -- name and kind together -- while a road or a railway is only named. Writing a
 * second screen for the place case would have duplicated the field, the focus handling and the
 * deferred-open dance that make this one work, and the two copies would drift. The type row and the
 * error line are therefore present only when a kind was supplied, and the name-only callers are
 * unaffected by either.
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
    /** Editing a place: a name and a kind. */
    public static final String TITLE_PLACE_EDIT = "screen.howtogo.place_edit";

    private static final int FIELD_WIDTH = 190;
    private static final int FIELD_HEIGHT = 18;
    private static final int PANEL_PAD = 5;
    private static final int TITLE_HEIGHT = 12;
    private static final int BUTTON_HEIGHT = 16;
    private static final int TYPE_HEIGHT = 16;
    private static final int TYPE_GAP = 3;
    /** Always reserved in the place editor, so the panel does not jump when a refusal appears. */
    private static final int ERROR_HEIGHT = 10;

    private final Screen parent;
    private final String titleKey;
    private final String initialValue;
    private final double anchorX;
    private final double anchorY;
    private final Consumer<String> onAccept;

    // The place editor's extra parts, all null for a name-only prompt.
    private final PlaceKind initialKind;
    private final Predicate<PlaceKind> kindAllowed;
    private final BiConsumer<String, PlaceKind> onAcceptPlace;

    private PlaceKind selectedKind;
    /** The message key shown when the chosen kind was refused, or null. */
    private String errorKey;

    private EditBox field;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int fieldX;
    private int fieldY;
    private int buttonY;

    /** Names only: roads, railways, and anything else with no kind. */
    public RoadNameScreen(Screen parent, String titleKey, String initialValue,
                          double anchorX, double anchorY, Consumer<String> onAccept) {
        super(Component.translatable(titleKey));
        this.parent = parent;
        this.titleKey = titleKey;
        this.initialValue = initialValue;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.onAccept = onAccept;
        this.initialKind = null;
        this.kindAllowed = null;
        this.onAcceptPlace = null;
    }

    /**
     * A place: a name and a kind.
     *
     * @param kindAllowed asked before anything is applied, so a refused kind keeps the panel open and
     *                    says why instead of silently closing on a change that did not happen
     */
    public RoadNameScreen(Screen parent, String titleKey, String initialValue, PlaceKind initialKind,
                          Predicate<PlaceKind> kindAllowed, double anchorX, double anchorY,
                          BiConsumer<String, PlaceKind> onAcceptPlace) {
        super(Component.translatable(titleKey));
        this.parent = parent;
        this.titleKey = titleKey;
        this.initialValue = initialValue;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.onAccept = null;
        this.initialKind = initialKind == null ? PlaceKind.PLACE : initialKind;
        this.kindAllowed = kindAllowed;
        this.onAcceptPlace = onAcceptPlace;
        this.selectedKind = this.initialKind;
    }

    private boolean isPlaceEditor() {
        return onAcceptPlace != null;
    }

    @Override
    protected void init() {
        int extra = isPlaceEditor() ? TYPE_HEIGHT + PANEL_PAD + ERROR_HEIGHT : 0;

        // Sit just to the right of the anchor, then keep the panel fully on screen. The extra height
        // is part of the clamp, or the type row would push the buttons off the bottom on a short window.
        fieldX = Mth.clamp((int) Math.round(anchorX) + 14, 4, Math.max(4, width - FIELD_WIDTH - 4));
        fieldY = Mth.clamp((int) Math.round(anchorY) - FIELD_HEIGHT / 2, 4,
                Math.max(4, height - FIELD_HEIGHT - extra - BUTTON_HEIGHT - 18));

        panelW = FIELD_WIDTH + PANEL_PAD * 2;
        panelH = TITLE_HEIGHT + FIELD_HEIGHT + extra + BUTTON_HEIGHT + PANEL_PAD * 3;
        panelX = fieldX - PANEL_PAD;
        panelY = fieldY - PANEL_PAD - TITLE_HEIGHT;

        field = new EditBox(this.font, fieldX, fieldY, FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable(titleKey));
        field.setMaxLength(48);
        field.setValue(initialValue == null ? "" : initialValue);
        addRenderableWidget(field);
        setInitialFocus(field);

        buttonY = fieldY + FIELD_HEIGHT + PANEL_PAD + extra;
        int half = (FIELD_WIDTH - PANEL_PAD) / 2;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> accept())
                .bounds(fieldX, buttonY, half, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(fieldX + half + PANEL_PAD, buttonY, half, BUTTON_HEIGHT).build());
    }

    private void accept() {
        if (!isPlaceEditor()) {
            onAccept.accept(field.getValue());
            onClose();
            return;
        }
        // Asked before applying. A refusal leaves the panel up with the reason on it, so the player
        // can pick another kind instead of losing the edit and wondering what happened.
        if (kindAllowed != null && !kindAllowed.test(selectedKind)) {
            errorKey = "screen.howtogo.station_needs_road";
            return;
        }
        onAcceptPlace.accept(field.getValue(), selectedKind);
        onClose();
    }

    // ------------------------------------------------------------- type row

    private int typeY() {
        return fieldY + FIELD_HEIGHT + PANEL_PAD;
    }

    private int typeWidth() {
        return (FIELD_WIDTH - (PlaceKind.values().length - 1) * TYPE_GAP)
                / PlaceKind.values().length;
    }

    private int typeX(int index) {
        return fieldX + index * (typeWidth() + TYPE_GAP);
    }

    private void drawTypes(GuiGraphics graphics, int mouseX, int mouseY) {
        PlaceKind[] kinds = PlaceKind.values();
        for (int i = 0; i < kinds.length; i++) {
            int x = typeX(i);
            int y = typeY();
            boolean selected = kinds[i] == selectedKind;
            boolean hovered = mouseX >= x && mouseX < x + typeWidth()
                    && mouseY >= y && mouseY < y + TYPE_HEIGHT;
            int background = selected ? 0xFF1F6FEB : (hovered ? 0xFF3A4450 : 0xFF242A33);
            graphics.fill(x, y, x + typeWidth(), y + TYPE_HEIGHT, background);

            String label = Component.translatable("screen.howtogo.place_kind." + kinds[i].id())
                    .getString();
            String shown = this.font.plainSubstrByWidth(label, typeWidth() - 2);
            graphics.drawString(this.font, shown,
                    x + Math.max(0, (typeWidth() - this.font.width(shown)) / 2), y + 4,
                    selected ? 0xFFFFFFFF : 0xFFA8B4C0, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isPlaceEditor() && button == 0) {
            PlaceKind[] kinds = PlaceKind.values();
            for (int i = 0; i < kinds.length; i++) {
                int x = typeX(i);
                int y = typeY();
                if (mouseX >= x && mouseX < x + typeWidth() && mouseY >= y
                        && mouseY < y + TYPE_HEIGHT) {
                    selectedKind = kinds[i];
                    // The old refusal was about the old choice, so it goes as soon as the choice does.
                    errorKey = null;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ---------------------------------------------------------------- screen

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
        if (isPlaceEditor()) {
            drawTypes(graphics, mouseX, mouseY);
            if (errorKey != null) {
                graphics.drawString(this.font, Component.translatable(errorKey).getString(), fieldX,
                        typeY() + TYPE_HEIGHT + 1, 0xFFFF6B6B, false);
            }
        }
    }
}
