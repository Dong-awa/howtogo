package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.place.PlaceDetails;
import bili.dongsz.howtogo.place.PlaceSources;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
// 1.21.9 bundled mouse coordinates and the button into a record.
//? if >=1.21.9 {
/*import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
*///?}
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;
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
 *
 * <h2>Why the details are a draft on this panel rather than its own commit</h2>
 * The place editor took on a third thing when places gained details: {@code More…} opens
 * {@link PlaceDetailScreen} as a draft, and the record it hands back is held here until {@code Done}.
 * Details stored from inside that dialog instead would have made this panel's {@code Cancel} untrue --
 * it would cancel the name and the kind while the description the player opened from its own button
 * was already on disk. {@code Done} on this panel still commits the place, so the three parts of a
 * place are one edit and one undo step, which is what they are to the player. Keeping the typed name
 * in a field (rather than in the widget alone) is what makes the round trip through the dialog lossless
 * -- the dialog replaces this screen, and {@code init} runs again on the way back.
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

    /**
     * What a committed place edit hands back: the name, the kind, and the details as edited.
     *
     * <p>One callback rather than the two-argument consumer this used to be, because the details are
     * written by the same step as the name and the kind: the place editor is one edit, and a caller
     * that had to save the details separately would be a caller that can save half of one.
     */
    public interface PlaceApply {
        void accept(String name, PlaceKind kind, PlaceDetails details);
    }

    private static final int FIELD_WIDTH = 190;
    private static final int FIELD_HEIGHT = 18;
    private static final int PANEL_PAD = 5;
    private static final int TITLE_HEIGHT = 12;
    private static final int BUTTON_HEIGHT = 16;
    private static final int TYPE_HEIGHT = 16;
    private static final int TYPE_GAP = 3;
    /** Always reserved in the place editor, so the panel does not jump when a refusal appears. */
    private static final int ERROR_HEIGHT = 10;
    /** The place editor's {@code More…} row, under the kind row and above the buttons. */
    private static final int MORE_HEIGHT = 16;
    private static final int MORE_GAP = 3;
    /** The storey's own box: three characters wide is enough for "-32" and keeps the row one line. */
    private static final int LAYER_FIELD_WIDTH = 42;

    private final Screen parent;
    private final String titleKey;
    private final String initialValue;
    private final double anchorX;
    private final double anchorY;
    private final Consumer<String> onAccept;

    // The place editor's extra parts, all null for a name-only prompt.
    private final PlaceKind initialKind;
    private final Predicate<PlaceKind> kindAllowed;
    private final PlaceApply onAcceptPlace;

    // The road editor's extra part: the storey, given with the name because they are edited together.
    private final int initialLayer;
    private final BiConsumer<String, Integer> onAcceptRoad;

    private PlaceKind selectedKind;
    /**
     * The details as edited so far, which is not the store's yet.
     *
     * <p>A field because the dialog that edits it replaces this screen and comes back to it, so
     * {@code init} runs again in between: anything kept only in a widget would be back at the store's
     * value by the time the panel is drawn a second time.
     */
    private PlaceDetails draftDetails;
    /**
     * The name as typed, kept for the same round trip.
     *
     * <p>Null until the name field has been read once; {@code init} falls back to the value the panel
     * was opened with.
     */
    private String typedName;
    /** The message shown when the last attempt was refused, or null. */
    private Component error;

    private EditBox field;
    /** The storey box, present only in the road editor. */
    private EditBox layerField;
    /** The {@code More…} button, present only in the place editor. */
    private PanelButton moreButton;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int fieldX;
    private int fieldY;
    private int buttonY;

    /** The done and cancel buttons, built in {@link #init()} and drawn and hit-tested by hand. */
    private List<PanelButton> buttons = List.of();

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
        this.initialLayer = 0;
        this.onAcceptRoad = null;
    }

    /**
     * A road: its name and which storey it is on, edited as one thing.
     *
     * <p>Together because they are one gesture -- point at a road, say what it is -- and because the
     * storey is invisible on a map that cannot show height: a player who had to remember a second key
     * for it would name the road and never find the other half. The storey field is checked before
     * anything is applied, so a mistyped level leaves the panel up with the reason on it rather than
     * closing on a change that did not happen.
     *
     * @param initialLayer the storey the road is on now, which the field starts at
     */
    public RoadNameScreen(Screen parent, String titleKey, String initialValue, int initialLayer,
                          double anchorX, double anchorY, BiConsumer<String, Integer> onAcceptRoad) {
        super(Component.translatable(titleKey));
        this.parent = parent;
        this.titleKey = titleKey;
        this.initialValue = initialValue;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.onAccept = null;
        this.initialKind = null;
        this.kindAllowed = null;
        this.onAcceptPlace = null;
        this.initialLayer = initialLayer;
        this.onAcceptRoad = onAcceptRoad;
    }

    /**
     * A place: a name, a kind, and the details edited so far.
     *
     * <p>The details arrive as a value rather than being read here, because the store is not this
     * screen's business: the caller that knows which destination stands for this place is the one
     * that can say what the player wrote about it. The record is then held as a draft until {@code
     * Done}: {@code More…} may replace it, and {@code Cancel} must be able to drop it.
     *
     * @param kindAllowed asked before anything is applied, so a refused kind keeps the panel open and
     *                    says why instead of silently closing on a change that did not happen
     */
    public RoadNameScreen(Screen parent, String titleKey, String initialValue, PlaceKind initialKind,
                          Predicate<PlaceKind> kindAllowed, PlaceDetails initialDetails,
                          double anchorX, double anchorY, PlaceApply onAcceptPlace) {
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
        this.initialLayer = 0;
        this.onAcceptRoad = null;
        this.draftDetails = initialDetails == null ? PlaceDetails.EMPTY : initialDetails;
    }

    private boolean isPlaceEditor() {
        return onAcceptPlace != null;
    }

    /** Whether this panel is editing a road: a name and a storey. */
    private boolean isRoadEditor() {
        return onAcceptRoad != null;
    }

    /**
     * The room the rows under the name take: the kind row or the storey row, the place editor's
     * {@code More…} row, and the error line.
     *
     * <p>One method rather than a condition in each of the places that need the number, because the
     * clamp that keeps the panel on screen and the height the panel is drawn at have to agree about
     * it -- and the place editor's row is what made them disagree when it was added.
     */
    private int extraRowsHeight() {
        if (isPlaceEditor()) {
            return TYPE_HEIGHT + MORE_GAP + MORE_HEIGHT + PANEL_PAD + ERROR_HEIGHT;
        }
        if (isRoadEditor()) {
            return TYPE_HEIGHT + PANEL_PAD + ERROR_HEIGHT;
        }
        return 0;
    }

    /**
     * Not a pause screen, which is the same trade the destination picker makes.
     *
     * <p>This panel is a small field beside the map: naming a road, setting a storey, typing a kind.
     * Freezing the world for it stops the player mid-step, stops the mobs, and stops the trip they
     * were in the middle of -- for a text field they will be done with in two seconds. Names are not
     * multiplayer-synchronised, so the host is not waiting either.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int extra = extraRowsHeight();

        // Sit just to the right of the anchor, then keep the panel fully on screen. The extra height
        // is part of the clamp, or the rows under the name would push the buttons off the bottom on a
        // short window.
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
        // The typed name survives the round trip through the details dialog: the dialog replaces this
        // screen, and this method runs again when it comes back. The initial value is only the first
        // time, before anything has been typed.
        field.setValue(typedName != null ? typedName : (initialValue == null ? "" : initialValue));
        addRenderableWidget(field);
        setInitialFocus(field);

        if (isRoadEditor()) {
            layerField = new EditBox(this.font, fieldX, extraRowY(), LAYER_FIELD_WIDTH, FIELD_HEIGHT,
                    Component.translatable("screen.howtogo.road_layer"));
            layerField.setMaxLength(3);
            layerField.setValue(String.valueOf(initialLayer));
            addRenderableWidget(layerField);
        }
        if (isPlaceEditor()) {
            // A row of its own rather than a third slot beside Done and Cancel: the dialog it opens
            // edits a different thing from the name field -- a description, tags, a favourite -- and
            // a control that is about the place rather than about closing the panel should not sit in
            // the row that closes it.
            moreButton = new PanelButton(fieldX, moreY(), FIELD_WIDTH, MORE_HEIGHT,
                    Component.translatable(PlaceDetailScreen.MORE_KEY), PanelButton.Kind.NEUTRAL,
                    this::openDetailsDraft);
        } else {
            moreButton = null;
        }

        buttonY = fieldY + FIELD_HEIGHT + PANEL_PAD + extra;
        int half = (FIELD_WIDTH - PANEL_PAD) / 2;
        // The same two slots, now drawn by this panel rather than handed to the widget list: a
        // vanilla button is a grey system widget on a sheet that is otherwise this mod's own, which
        // the white style made the one thing on the panel that did not belong to it. Done is the
        // action the panel exists for, so it takes the accent; cancel is ordinary.
        buttons = List.of(
                new PanelButton(fieldX, buttonY, half, BUTTON_HEIGHT,
                        Component.translatable("gui.done"), PanelButton.Kind.PRIMARY, this::accept),
                new PanelButton(fieldX + half + PANEL_PAD, buttonY, half, BUTTON_HEIGHT,
                        Component.translatable("gui.cancel"), PanelButton.Kind.NEUTRAL,
                        this::onClose));
    }

    /**
     * Opens the details dialog as a draft, keeping the name and the kind as they stand.
     *
     * <p>The dialog is a draft because this panel is the one thing that commits a place edit: its
     * {@code Done} writes name, kind and details together, and its {@code Cancel} drops all three. The
     * dialog's own {@code Done} therefore only records what was written here, and its {@code Cancel}
     * only forgets the details.
     */
    private void openDetailsDraft() {
        typedName = field.getValue();
        PlaceDetailScreen.openForDraft(this, draftPlaceName(), draftDetails,
                details -> draftDetails = details);
    }

    /**
     * The destination the draft dialog is shown for.
     *
     * <p>Only its name and its source are read: the dialog shows the name, says where the place came
     * from, and writes nothing in draft mode. The coordinates are therefore the placeholder the record
     * wants rather than a measurement, and the source is this panel's own -- a hand-placed place,
     * which is the only kind this panel edits. Building the real destination here would mean passing
     * the whole network node into a screen that must not know what a node is.
     */
    private Destination draftPlaceName() {
        return new Destination(field.getValue(), 0, 0, 0, PlaceSources.MANUAL, Destination.NO_COLOR,
                selectedKind, null);
    }

    private void accept() {
        if (isRoadEditor()) {
            // Asked before applying, and the panel stays up when the answer is no: a storey outside the
            // range, or something that is not a number at all, is a keystroke away from being right.
            Integer layer = parseLayer(layerField.getValue());
            if (layer == null) {
                error = Component.translatable("screen.howtogo.layer.bad_number", layerField.getValue());
                return;
            }
            if (layer < RoadSegment.MIN_LAYER || layer > RoadSegment.MAX_LAYER) {
                error = Component.translatable("screen.howtogo.layer.out_of_range",
                        RoadSegment.MIN_LAYER, RoadSegment.MAX_LAYER);
                return;
            }
            onAcceptRoad.accept(field.getValue(), layer);
            onClose();
            return;
        }
        if (!isPlaceEditor()) {
            onAccept.accept(field.getValue());
            onClose();
            return;
        }
        // Asked before applying. A refusal leaves the panel up with the reason on it, so the player
        // can pick another kind instead of losing the edit and wondering what happened.
        if (kindAllowed != null && !kindAllowed.test(selectedKind)) {
            error = Component.translatable("screen.howtogo.station_needs_road");
            return;
        }
        // The details go with the name and the kind: they are the third part of the same edit, and a
        // caller that saved them itself would make this panel's cancel only two-thirds of a cancel.
        onAcceptPlace.accept(field.getValue(), selectedKind, draftDetails);
        onClose();
    }

    /** The typed storey, or null when the text is not a number. */
    private static Integer parseLayer(String typed) {
        String trimmed = typed == null ? "" : typed.trim();
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    // ------------------------------------------------------------- type row

    /** Where the row under the name sits, whichever kind of row it is. */
    private int extraRowY() {
        return fieldY + FIELD_HEIGHT + PANEL_PAD;
    }

    /** Where the place editor's {@code More…} row sits: under the kind row, with the panel's own gap. */
    private int moreY() {
        return extraRowY() + TYPE_HEIGHT + MORE_GAP;
    }

    /**
     * Where the error line sits: under everything the panel has above the buttons.
     *
     * <p>Derived rather than written out, because the place editor gained a row and the line that used
     * to be measured from the kind row would have been drawn through it.
     */
    private int errorY() {
        return isPlaceEditor() ? moreY() + MORE_HEIGHT + 1 : extraRowY() + TYPE_HEIGHT + 1;
    }

    /**
     * The storey row: the box, and beside it either the name of the storey typed in it or, while the
     * text is not a number, the range the box takes.
     *
     * <p>Live rather than as a second label, because the number is the thing being chosen and "2"
     * tells a player nothing about whether they have the second overpass or the second basement.
     */
    private void drawLayerRow(GuiGraphics graphics) {
        int y = extraRowY();
        Integer typed = parseLayer(layerField.getValue());
        String beside;
        int colour;
        if (typed == null) {
            beside = Component.translatable("screen.howtogo.road_layer").getString();
            colour = UiTheme.textFaint();
        } else if (typed < RoadSegment.MIN_LAYER || typed > RoadSegment.MAX_LAYER) {
            beside = Component.translatable("screen.howtogo.layer.out_of_range",
                    RoadSegment.MIN_LAYER, RoadSegment.MAX_LAYER).getString();
            colour = UiTheme.error();
        } else {
            beside = Navigation.roadLayerLabel(typed);
            colour = UiTheme.accentSoft();
        }
        graphics.drawString(this.font, this.font.plainSubstrByWidth(beside,
                        FIELD_WIDTH - LAYER_FIELD_WIDTH - 4), fieldX + LAYER_FIELD_WIDTH + 4, y + 4,
                colour, false);
    }

    private int typeY() {
        return extraRowY();
    }

    private int typeWidth() {
        return (FIELD_WIDTH - (PlaceKind.values().length - 1) * TYPE_GAP)
                / PlaceKind.values().length;
    }

    private int typeX(int index) {
        return fieldX + index * (typeWidth() + TYPE_GAP);
    }

    /**
     * The row of place kinds, the one control on this panel that has a chosen state.
     *
     * <p>The faces are the button roles rather than the row roles, because a chip is a control and a
     * row is not: on the white sheet the old row colour was white on a near-white panel, so an
     * unselected kind had no face at all -- its label appeared to float on the sheet. The chosen one
     * takes {@link UiTheme#selectionSoft()} rather than the full {@link UiTheme#selection()} of a
     * picked list row: four solid accent blocks in one row read as four primary actions, and the one
     * that is merely the current choice would shout as loudly as the button that commits the edit.
     */
    private void drawTypes(GuiGraphics graphics, int mouseX, int mouseY) {
        PlaceKind[] kinds = PlaceKind.values();
        for (int i = 0; i < kinds.length; i++) {
            int x = typeX(i);
            int y = typeY();
            boolean selected = kinds[i] == selectedKind;
            boolean hovered = mouseX >= x && mouseX < x + typeWidth()
                    && mouseY >= y && mouseY < y + TYPE_HEIGHT;
            int background = selected ? UiTheme.selectionSoft()
                    : (hovered ? UiTheme.buttonHover() : UiTheme.button());
            graphics.fill(x, y, x + typeWidth(), y + TYPE_HEIGHT, background);

            String label = Component.translatable("screen.howtogo.place_kind." + kinds[i].id())
                    .getString();
            String shown = this.font.plainSubstrByWidth(label, typeWidth() - 2);
            graphics.drawString(this.font, shown,
                    x + Math.max(0, (typeWidth() - this.font.width(shown)) / 2), y + 4,
                    selected ? UiTheme.onSelectionSoft() : UiTheme.textSecondary(), false);
        }
    }

    @Override
    //? if >=1.21.9 {
    /*public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
    *///?} else {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //?}
        // The done and cancel buttons are drawn, not widgets, so this is their only chance to see a
        // click; it comes before the kind row so a press on one of them is never also a change of
        // kind under it.
        if (button == 0) {
            for (PanelButton candidate : buttons) {
                if (candidate.click(mouseX, mouseY)) {
                    return true;
                }
            }
            if (moreButton != null && moreButton.click(mouseX, mouseY)) {
                return true;
            }
        }
        if (isPlaceEditor() && button == 0) {
            PlaceKind[] kinds = PlaceKind.values();
            for (int i = 0; i < kinds.length; i++) {
                int x = typeX(i);
                int y = typeY();
                if (mouseX >= x && mouseX < x + typeWidth() && mouseY >= y
                        && mouseY < y + TYPE_HEIGHT) {
                    selectedKind = kinds[i];
                    // The old refusal was about the old choice, so it goes as soon as the choice does.
                    error = null;
                    return true;
                }
            }
        }
        //? if >=1.21.9 {
            /*return super.mouseClicked(new MouseButtonEvent(mouseX, mouseY,
                    new MouseButtonInfo(button, 0)), false);
            *///?} else {
            return super.mouseClicked(mouseX, mouseY, button);
            //?}
    }

    // ---------------------------------------------------------------- screen

    @Override
    // 1.21.9 made key input a record and moved the key codes onto the SDL-backed InputConstants.
    // The dispatch still wants the code as a number -- it compares against three of them -- so the
    // record is unpacked on entry and the body below reads the same on either side.
    //? if >=1.21.9 {
    /*public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key();
    *///?} else {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    //?}
        // Enter confirms, so a road can be named and given a storey without reaching for the mouse.
        if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
            accept();
            return true;
        }
        // Tab between the two fields of the road editor. A plain Screen has no tab order of its own, so
        // without this the storey box could only be reached with the mouse -- the wrong half of the
        // panel to make the player reach for, and the one an input method does not help with either.
        if (keyCode == InputConstants.KEY_TAB && layerField != null) {
            setFocused(getFocused() == layerField ? field : layerField);
            return true;
        }
        //? if >=1.21.9 {
        /*return super.keyPressed(event);
        *///?} else {
        return super.keyPressed(keyCode, scanCode, modifiers);
        //?}
    }

    @Override
    public void onClose() {
        Screens.set(parent);
    }

    /** No dim, no blur: the map underneath is the context for the name being typed. */
    @Override
    // 1.20.2 added the mouse position and the partial tick to this override; 1.20.1 takes only the
    // graphics. Both do nothing here -- the dimming is handled in render so the panel sits between
    // it and the widgets -- so the bodies are the same and only the signature differs.
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}
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

        // A window edge and an opaque sheet rather than a tint over the map: the prompt has to be
        // readable on top of whatever the map happens to be drawing under it, and the title is the
        // first thing on it that says what is being named.
        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1,
                UiTheme.windowEdge());
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, UiTheme.panelSolid());
        graphics.drawString(this.font, this.title, panelX + PANEL_PAD, panelY + PANEL_PAD,
                UiTheme.title(), false);

        super.render(graphics, mouseX, mouseY, partialTick);
        if (isPlaceEditor()) {
            drawTypes(graphics, mouseX, mouseY);
            if (moreButton != null) {
                // Between the kind row and the error line, and before the buttons: it is an action on
                // the place rather than one of the two that close the panel, so it sits with the
                // fields it edits.
                moreButton.render(graphics, this.font, mouseX, mouseY);
            }
        } else if (isRoadEditor()) {
            drawLayerRow(graphics);
        }
        if (error != null) {
            graphics.drawString(this.font, error.getString(), fieldX, errorY(), UiTheme.error(),
                    false);
        }
        // The panel's last row, drawn last: the kind row and the error line above it are this class's
        // own drawing, and a button painted before its own row of chips could have one across it.
        drawButtons(graphics, mouseX, mouseY);
    }

    /**
     * The done and cancel buttons.
     *
     * <p>Drawn rather than handed to the widget list, which is why {@link #render} has to draw them
     * itself and {@link #mouseClicked} has to route the click to them: see {@link PanelButton} for the
     * trade that comes with that. The panel under them is drawn by {@link #render} before the text,
     * so they land on the sheet rather than under it.
     */
    private void drawButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        for (PanelButton button : buttons) {
            button.render(graphics, this.font, mouseX, mouseY);
        }
    }

}
