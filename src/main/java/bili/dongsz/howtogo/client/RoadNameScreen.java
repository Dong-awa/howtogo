package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.place.PlaceDetails;
import bili.dongsz.howtogo.place.PlaceSources;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadCategory;
import bili.dongsz.howtogo.road.RoadDirection;
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
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Compact inline prompt for naming a road, or for editing a place. The map is re-rendered underneath and only a small panel is drawn beside the anchor, so naming does not interrupt navigation. A real EditBox is used so the platform input method works for non-ASCII names.
 * A place is edited (name, kind, details) while a road is only named; the kind row and the error line exist only when a kind was supplied. Details are a draft held here until Done so one Cancel drops all three parts, and the typed name is kept in a field so the round trip through the details dialog is lossless.
 * A road's five values are one panel because they are one answer to "what is this road"; splitting them would let a player name a road and never find the rest. Category and direction are steppers (click, wheel or arrow keys); the number is free text, see RoadSegment.number().
 * The number waits for a real category: the built-in not-classified category has no sign to print a plate on, so the field is disabled until one is chosen, keeping its text while stepping through. The category list comes from RoadCategoryStore and normalises deleted ids to the built-in category.
 */
public final class RoadNameScreen extends Screen implements ParentBackdrop {

    public static final String TITLE_ROAD = "screen.howtogo.name_road";
    public static final String TITLE_POI = "screen.howtogo.name_poi";
    /**
     * Naming an automatically detected railway. Its own title confirms the click landed on the rail layer rather than on a road.
     */
    public static final String TITLE_RAIL = "screen.howtogo.name_rail";
    /** Editing a place: a name and a kind. */
    public static final String TITLE_PLACE_EDIT = "screen.howtogo.place_edit";
    /**
     * What a committed place edit hands back: name, kind and details. One callback because the place editor is one edit -- a caller saving details separately could save half of one.
     */
    public interface PlaceApply {
        void accept(String name, PlaceKind kind, PlaceDetails details);
    }

    /**
     * What a committed road edit hands back: name, storey, category, number and direction. One callback because the five values are a single answer; the number is null under the built-in category (RoadSegment.hasSign draws no sign), and the direction is chain-relative as RoadEditor.setChainDirection takes it.
     */
    public interface RoadApply {
        void accept(String name, int layer, RoadCategory category, String number,
                    RoadDirection direction);
    }

    private static final int FIELD_WIDTH = 190;
    private static final int FIELD_HEIGHT = 18;
    private static final int PANEL_PAD = 5;
    private static final int TITLE_HEIGHT = 12;
    private static final int BUTTON_HEIGHT = 16;
    private static final int TYPE_HEIGHT = 16;
    private static final int TYPE_GAP = 3;
    private static final int ERROR_HEIGHT = 10;
    private static final int MORE_HEIGHT = 16;
    private static final int MORE_GAP = 3;
    private static final int LAYER_FIELD_WIDTH = 42;
    /**
     * The room the category and number rows share on the left for their labels; a text field cannot carry a label of its own.
     */
    private static final int DETAIL_LABEL_WIDTH = 62;
    private static final int DETAIL_GAP = 4;
    private static final int ROW_GAP = 3;
    /** Category row height; shorter than a field because it is a stepper, leaving more map visible. */
    private static final int CATEGORY_HEIGHT = 16;
    /**
     * The longest road number the field takes -- a letter, dash and district suffix, not a paragraph. Only the length is enforced; the number is free text.
     */
    private static final int NUMBER_LENGTH = 24;

    private final Screen parent;
    private final String titleKey;
    private final String initialValue;
    private final double anchorX;
    private final double anchorY;
    private final Consumer<String> onAccept;

    private final PlaceKind initialKind;
    private final Predicate<PlaceKind> kindAllowed;
    private final PlaceApply onAcceptPlace;
    /**
     * What to do when the panel is cancelled, for an edit with something to take back. Null unless the place was created by the press that opened this panel, where Cancel must remove it.
     */
    private final Runnable onCancel;
    /**
     * Whether this visit ended in Done; keeps onCancel from firing after a successful edit, since both endings close through onClose.
     */
    private boolean committed;

    // The road editor's extra parts: the storey, the category and the number, given with the name
    // because they are edited together.
    private final int initialLayer;
    private final String initialNumber;
    /**
     * The categories of the edited road's class, taken once at construction. Store order: built-in first, then player-made in creation order, so stepping off the end un-classifies.
     */
    private final List<RoadCategory> categories;
    private final RoadApply onAcceptRoad;

    /**
     * The category chosen so far, the road's own when the panel opens (normalised: a deleted id opens the built-in one). Read by the number field's enabled state, and a field so it survives init and the hand-drawn picker.
     */
    private RoadCategory selectedCategory;

    /**
     * The direction the edited road runs, the chain's own rather than the piece's -- a bent street is several segments. Asked of the chain when the panel opens and kept in a field because it survives init.
     */
    private RoadDirection selectedDirection;

    /** One hover slide per half of the direction box: the two halves step opposite ways. */
    private final UiMotion.Slides directionHovers = new UiMotion.Slides();

    private PlaceKind selectedKind;
    /**
     * The details as edited so far, not the store's yet. A field because the dialog that edits them replaces this screen and init runs again in between.
     */
    private PlaceDetails draftDetails;
    /** The name as typed, kept for the same round trip; null until the field has been read once. */
    private String typedName;
    private Component error;

    private EditBox field;
    private EditBox layerField;
    private EditBox numberField;
    private PanelButton moreButton;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int fieldX;
    private int fieldY;
    private int buttonY;

    private List<PanelButton> buttons = List.of();

    private float open = 1.0F;

    /** One hover slide per kind chip, keyed by position so a row swept across never allocates. */
    private final UiMotion.Slides kindHovers = new UiMotion.Slides();

    /** One hover slide per half of the category box: the left steps back and the right steps forward. */
    private final UiMotion.Slides categoryHovers = new UiMotion.Slides();

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
        this.initialNumber = null;
        this.categories = List.of();
        this.onAcceptRoad = null;
        this.onCancel = null;
    }

    /**
     * A road: name, storey, category, number and direction, edited as one gesture. The storey field is checked before anything is applied, so a mistyped level leaves the panel up with the reason. The category is normalised first because a road may carry a deleted id, and the list is read from RoadCategoryStore in the constructor.
     * @param initialLayer     the storey the road is on now
     * @param initialCategory  the category it carries now; null or a deleted id opens on the class's built-in category
     * @param initialNumber    the number it carries now, or null
     * @param initialDirection the way the whole road runs, as the chain's direction
     */
    public RoadNameScreen(Screen parent, String titleKey, String initialValue, int initialLayer,
                          RoadCategory initialCategory, String initialNumber,
                          RoadDirection initialDirection,
                          double anchorX, double anchorY, RoadApply onAcceptRoad) {
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
        // Normalised before anything reads it: a null category cannot say which class the road is, and a deleted id draws as the built-in category everywhere else.
        RoadCategory category = RoadCategoryStore.normalise(initialCategory);
        this.initialNumber = initialNumber;
        this.initialLayer = initialLayer;
        this.selectedDirection = initialDirection == null
                ? RoadDirection.TWO_WAY : initialDirection;
        // The store's own order, built-in first; copied so a category added while the panel is open cannot appear mid-step.
        this.categories = List.copyOf(RoadCategoryStore.ofClass(category.roadClass()));
        this.selectedCategory = category;
        this.onAcceptRoad = onAcceptRoad;
        this.onCancel = null;
    }

    /**
     * A place: name, kind and details. Details arrive as a value because the store is not this screen's business; the record is held as a draft until Done so Cancel can drop it.
     * @param kindAllowed asked before applying, so a refused kind keeps the panel open with the reason
     */
    public RoadNameScreen(Screen parent, String titleKey, String initialValue, PlaceKind initialKind,
                          Predicate<PlaceKind> kindAllowed, PlaceDetails initialDetails,
                          double anchorX, double anchorY, PlaceApply onAcceptPlace) {
        this(parent, titleKey, initialValue, initialKind, kindAllowed, initialDetails, anchorX,
                anchorY, onAcceptPlace, null);
    }

    /**
     * The same, plus what cancelling takes back: used when the place was created by the press that opened the panel, so Cancel and Escape must remove it.
     * @param onCancel called when the panel is closed without Done, or null when there is nothing to undo
     */
    public RoadNameScreen(Screen parent, String titleKey, String initialValue, PlaceKind initialKind,
                          Predicate<PlaceKind> kindAllowed, PlaceDetails initialDetails,
                          double anchorX, double anchorY, PlaceApply onAcceptPlace,
                          Runnable onCancel) {
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
        this.initialNumber = null;
        this.categories = List.of();
        this.onAcceptRoad = null;
        this.onCancel = onCancel;
        this.draftDetails = initialDetails == null ? PlaceDetails.EMPTY : initialDetails;
    }

    private boolean isPlaceEditor() {
        return onAcceptPlace != null;
    }

    /** Whether this panel is editing a road: a name, a storey, a category and a number. */
    private boolean isRoadEditor() {
        return onAcceptRoad != null;
    }

    /**
     * The room the rows under the name take -- the kind row or the road's three rows, the More row and the error line. One method so the on-screen clamp and the drawn height agree.
     */
    private int extraRowsHeight() {
        if (isPlaceEditor()) {
            return TYPE_HEIGHT + MORE_GAP + MORE_HEIGHT + PANEL_PAD + ERROR_HEIGHT;
        }
        if (isRoadEditor()) {
            // Measured from real heights: FIELD_HEIGHT boxes and CATEGORY_HEIGHT rows sum to the drawn panel.
            return FIELD_HEIGHT + ROW_GAP + CATEGORY_HEIGHT + ROW_GAP + FIELD_HEIGHT
                    + ROW_GAP + CATEGORY_HEIGHT + PANEL_PAD + ERROR_HEIGHT;
        }
        return 0;
    }

    /**
     * Not a pause screen: freezing the world for a two-second text field would stop the player's trip, and names are not synchronised, so the host is not waiting either.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int extra = extraRowsHeight();

        // Sit just right of the anchor, then keep the panel fully on screen.
        int panelTop = PANEL_PAD + TITLE_HEIGHT;
        fieldX = Mth.clamp((int) Math.round(anchorX) + 14, PANEL_PAD,
                Math.max(PANEL_PAD, width - FIELD_WIDTH - PANEL_PAD));
        fieldY = Mth.clamp((int) Math.round(anchorY) - FIELD_HEIGHT / 2, panelTop,
                Math.max(panelTop, height - FIELD_HEIGHT - extra - BUTTON_HEIGHT - 18));

        panelW = FIELD_WIDTH + PANEL_PAD * 2;
        panelH = TITLE_HEIGHT + FIELD_HEIGHT + extra + BUTTON_HEIGHT + PANEL_PAD * 3;
        panelX = fieldX - PANEL_PAD;
        panelY = fieldY - PANEL_PAD - TITLE_HEIGHT;

        field = new EditBox(this.font, fieldX, fieldY, FIELD_WIDTH, FIELD_HEIGHT,
                Component.translatable(titleKey));
        field.setMaxLength(48);
        // The typed name survives the details-dialog round trip; initialValue only seeds the first init.
        field.setValue(typedName != null ? typedName : (initialValue == null ? "" : initialValue));
        addRenderableWidget(field);
        setInitialFocus(field);

        if (isRoadEditor()) {
            layerField = new EditBox(this.font, fieldX, extraRowY(), LAYER_FIELD_WIDTH, FIELD_HEIGHT,
                    Component.translatable("screen.howtogo.road_layer"));
            layerField.setMaxLength(3);
            layerField.setValue(String.valueOf(initialLayer));
            addRenderableWidget(layerField);
            // The typed number survives a re-init on resize: read it off the old box before the widgets are rebuilt.
            String typedNumber = numberField == null ? null : numberField.getValue();
            numberField = new EditBox(this.font, detailX(), numberRowY(), detailWidth(),
                    FIELD_HEIGHT, Component.translatable("screen.howtogo.road_number"));
            numberField.setMaxLength(NUMBER_LENGTH);
            numberField.setValue(typedNumber != null ? typedNumber
                    : (initialNumber == null ? "" : initialNumber));
            addRenderableWidget(numberField);
            // The uneditable colour is the theme's faint rank, so a disabled field reads as future, not ordinary.
            numberField.setTextColorUneditable(UiTheme.textFaint());
            updateNumberAvailability();
        }
        if (isPlaceEditor()) {
            // A row of its own: the dialog it opens edits the place, not the panel's ending.
            moreButton = new PanelButton(fieldX, moreY(), FIELD_WIDTH, MORE_HEIGHT,
                    Component.translatable(PlaceDetailScreen.MORE_KEY), PanelButton.Kind.NEUTRAL,
                    this::openDetailsDraft);
        } else {
            moreButton = null;
        }

        buttonY = fieldY + FIELD_HEIGHT + PANEL_PAD + extra;
        int half = (FIELD_WIDTH - PANEL_PAD) / 2;
        // The same two slots drawn by this panel, so Done takes the accent and Cancel is ordinary.
        buttons = List.of(
                new PanelButton(fieldX, buttonY, half, BUTTON_HEIGHT,
                        Component.translatable("gui.done"), PanelButton.Kind.PRIMARY, this::accept),
                new PanelButton(fieldX + half + PANEL_PAD, buttonY, half, BUTTON_HEIGHT,
                        Component.translatable("gui.cancel"), PanelButton.Kind.NEUTRAL,
                        this::onClose));
    }

    /**
     * Opens the details dialog as a draft, keeping the name and kind. This panel is the only committer: Done writes name, kind and details, and Cancel drops all three.
     */
    private void openDetailsDraft() {
        typedName = field.getValue();
        PlaceDetailScreen.openForDraft(this, draftPlaceName(), draftDetails,
                details -> draftDetails = details);
    }

    /**
     * The destination the draft dialog is shown for. Only its name and source are read, so the coordinates are a placeholder; the real node must not enter a screen.
     */
    private Destination draftPlaceName() {
        return new Destination(field.getValue(), 0, 0, 0, PlaceSources.MANUAL, Destination.NO_COLOR,
                selectedKind, null);
    }

    private void accept() {
        if (isRoadEditor()) {
            // Checked before applying; a bad storey keeps the panel up, since it is a keystroke away from right.
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
            // All five values go back in one call, so the caller writes them as one undo step. The number is the typed text under a real category and null under the built-in one: keeping it would store a value the map never draws and that would reappear after the player was told the road has no number.
            String plate = selectedCategory.isNone() || numberField == null
                    ? null : numberField.getValue();
            onAcceptRoad.accept(field.getValue(), layer, selectedCategory, plate,
                    selectedDirection);
            committed = true;
            onClose();
            return;
        }
        if (!isPlaceEditor()) {
            onAccept.accept(field.getValue());
            committed = true;
            onClose();
            return;
        }
        // Asked before applying; a refusal leaves the panel up with the reason so another kind can be picked.
        if (kindAllowed != null && !kindAllowed.test(selectedKind)) {
            error = Component.translatable("screen.howtogo.station_needs_road");
            return;
        }
        // The details go with name and kind: they are the third part of the same edit.
        onAcceptPlace.accept(field.getValue(), selectedKind, draftDetails);
        committed = true;
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

    /** Where the place editor's More… row sits: under the kind row, with the panel's own gap. */
    private int moreY() {
        return extraRowY() + TYPE_HEIGHT + MORE_GAP;
    }

    /** Measured from the storey box's real FIELD_HEIGHT, or the category face would overlap it. */
    private int categoryRowY() {
        return extraRowY() + FIELD_HEIGHT + ROW_GAP;
    }

    /** Where the number box sits: under the category row, above the one-way row. */
    private int numberRowY() {
        return categoryRowY() + CATEGORY_HEIGHT + ROW_GAP;
    }

    /** The one-way row is last: it is the only control here that is not a text field. */
    private int directionRowY() {
        return numberRowY() + FIELD_HEIGHT + ROW_GAP;
    }

    /** Where the detail rows' labels end and their controls begin. */
    private int detailX() {
        return fieldX + DETAIL_LABEL_WIDTH + DETAIL_GAP;
    }

    /** The width the category box and the number box share. */
    private int detailWidth() {
        return FIELD_WIDTH - DETAIL_LABEL_WIDTH - DETAIL_GAP;
    }

    /** Derived rather than written out, so the error line stays under whatever rows came after it. */
    private int errorY() {
        if (isPlaceEditor()) {
            return moreY() + MORE_HEIGHT + 1;
        }
        if (isRoadEditor()) {
            return directionRowY() + CATEGORY_HEIGHT + 1;
        }
        return extraRowY() + TYPE_HEIGHT + 1;
    }

    /** The storey row: the box, and beside it the storey's name or its valid range. */
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
                UiMotion.fade(colour, open), false);
    }

    // -------------------------------------------------- category and number rows

    /** Whether the point is on the category box, which is the only part of its row that steps. */
    private boolean overCategory(double mouseX, double mouseY) {
        int x = detailX();
        int y = categoryRowY();
        return mouseX >= x && mouseX < x + detailWidth()
                && mouseY >= y && mouseY < y + CATEGORY_HEIGHT;
    }

    /**
     * Steps the category picker by one, wrapping within the class's own categories. Wrapping because the list is short and circular; compared by value because the store hands out a fresh (class, id) value on every read, and identity would reset the step.
     */
    private void stepCategory(int delta) {
        if (categories.isEmpty()) {
            return;
        }
        int index = categories.indexOf(selectedCategory);
        if (index < 0) {
            // Defensive: the constructor normalises, so this only happens if the list did not contain the value.
            index = 0;
        }
        selectedCategory = categories.get(Math.floorMod(index + delta, categories.size()));
        // Stepping across the built-in category enables or disables the number.
        updateNumberAvailability();
    }

    /**
     * Makes the number field usable only while the road is classified: an unclassified road has no sign to print on. Disabled rather than hidden or cleared, so the typed text survives a step through the built-in category.
     */
    private void updateNumberAvailability() {
        if (numberField == null) {
            return;
        }
        numberField.setEditable(!selectedCategory.isNone());
    }

    // ---------------------------------------------------------- one-way row

    /** Whether the point is on the one-way row's box, which is the whole of that row's control. */
    private boolean overDirection(double mouseX, double mouseY) {
        int x = detailX();
        int y = directionRowY();
        return mouseX >= x && mouseX < x + detailWidth()
                && mouseY >= y && mouseY < y + CATEGORY_HEIGHT;
    }

    /**
     * Steps the one-way state by one: two-way, along the road, against it. The same three-state cycle the O key walks; the state is chain-relative, as RoadEditor writes it onto every piece.
     */
    private void stepDirection(int delta) {
        selectedDirection = delta > 0 ? selectedDirection.next()
                : selectedDirection.next().next();
        error = null;
    }

    /**
     * The one-way row: the label and a box whose two halves step the state. Its own row because the O key is a shortcut a player must be told about; the box matches the category row so both read as the same control.
     */
    private void drawDirectionRow(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = directionRowY();
        drawDetailLabel(graphics, "screen.howtogo.road_direction", y, CATEGORY_HEIGHT);

        int x = detailX();
        int width = detailWidth();
        boolean over = overDirection(mouseX, mouseY);
        boolean overLeft = over && mouseX < x + width / 2;
        boolean overRight = over && mouseX >= x + width / 2;
        float litLeft = directionHovers.get(0).to(overLeft ? 1.0F : 0.0F).eased();
        float litRight = directionHovers.get(1).to(overRight ? 1.0F : 0.0F).eased();
        int face = UiMotion.mix(UiTheme.button(), UiTheme.buttonHover(),
                Math.max(litLeft, litRight));
        HudDraw.fillRounded(graphics, x, y, x + width, y + CATEGORY_HEIGHT, 3,
                UiMotion.fade(face, open));

        int textY = y + (CATEGORY_HEIGHT - 8) / 2;
        graphics.drawString(this.font, "<", x + 3, textY,
                UiMotion.fade(UiMotion.mix(UiTheme.textFaint(), UiTheme.textSecondary(), litLeft),
                        open), false);
        graphics.drawString(this.font, ">", x + width - 3 - this.font.width(">"), textY,
                UiMotion.fade(UiMotion.mix(UiTheme.textFaint(), UiTheme.textSecondary(), litRight),
                        open), false);

        // The direction's word, coloured by direction and centred in the room the two chevrons leave.
        String label = Component.translatable("hud.howtogo.direction." + selectedDirection.id())
                .getString();
        String shown = PlaceText.ellipsis(this.font, label, width - 24 - 11);
        graphics.drawString(this.font, shown,
                x + (width - this.font.width(shown)) / 2, textY,
                UiMotion.fade(UiTheme.text(), open), false);
    }

    /**
     * The small word at the left of a detail row, cut to the label column so a longer translation cannot reach into the control beside it.
     */
    private void drawDetailLabel(GuiGraphics graphics, String langKey, int rowY, int rowHeight) {
        String label = PlaceText.ellipsis(this.font, Component.translatable(langKey).getString(),
                DETAIL_LABEL_WIDTH - 4);
        graphics.drawString(this.font, label, fieldX, rowY + (rowHeight - 8) / 2,
                UiMotion.fade(UiTheme.textSecondary(), open), false);
    }

    /**
     * The category row: label plus a box that is both readout and stepper. Two clickable halves with chevrons; the swatch shows the sign's colour, which the name alone cannot.
     */
    private void drawCategoryRow(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = categoryRowY();
        drawDetailLabel(graphics, "screen.howtogo.road_category", y, CATEGORY_HEIGHT);

        int x = detailX();
        int width = detailWidth();
        boolean over = overCategory(mouseX, mouseY);
        boolean overLeft = over && mouseX < x + width / 2;
        boolean overRight = over && mouseX >= x + width / 2;
        // One slide per half, so a sweep lights the half under the cursor, not the whole face.
        float litLeft = categoryHovers.get(0).to(overLeft ? 1.0F : 0.0F).eased();
        float litRight = categoryHovers.get(1).to(overRight ? 1.0F : 0.0F).eased();
        int face = UiMotion.mix(UiTheme.button(), UiTheme.buttonHover(),
                Math.max(litLeft, litRight));
        HudDraw.fillRounded(graphics, x, y, x + width, y + CATEGORY_HEIGHT, 3,
                UiMotion.fade(face, open));

        int textY = y + (CATEGORY_HEIGHT - 8) / 2;
        // Chevrons mix from faint to secondary by half: the side about to be clicked darkens.
        graphics.drawString(this.font, "<", x + 3, textY,
                UiMotion.fade(UiMotion.mix(UiTheme.textFaint(), UiTheme.textSecondary(), litLeft),
                        open), false);
        graphics.drawString(this.font, ">", x + width - 3 - this.font.width(">"), textY,
                UiMotion.fade(UiMotion.mix(UiTheme.textFaint(), UiTheme.textSecondary(), litRight),
                        open), false);

        // The marker first, then the name, cut to the room the marker and right chevron leave.
        HudDraw.drawRowMarker(graphics, x + 12, y + (CATEGORY_HEIGHT - 9) / 2, 9,
                UiMotion.fade(RoadCategoryStore.color(selectedCategory), open));
        String name = RoadCategoryStore.name(selectedCategory);
        graphics.drawString(this.font, PlaceText.ellipsis(this.font, name, width - 24 - 13),
                x + 24, textY, UiMotion.fade(UiTheme.text(), open), false);
    }

    /**
     * The number row's label; the field is a real EditBox and draws itself, which is what gives it the platform input method. Nothing is written beside a disabled field -- an unclassified road has no number to give.
     */
    private void drawNumberRow(GuiGraphics graphics) {
        drawDetailLabel(graphics, "screen.howtogo.road_number", numberRowY(), FIELD_HEIGHT);
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
     * The row of place kinds. Chips use button roles rather than row roles so an unselected chip has a face on the white sheet; the chosen one uses selectionSoft because four solid accents would read as four primary actions.
     */
    private void drawTypes(GuiGraphics graphics, int mouseX, int mouseY) {
        PlaceKind[] kinds = PlaceKind.values();
        for (int i = 0; i < kinds.length; i++) {
            int x = typeX(i);
            int y = typeY();
            boolean selected = kinds[i] == selectedKind;
            boolean hovered = mouseX >= x && mouseX < x + typeWidth()
                    && mouseY >= y && mouseY < y + TYPE_HEIGHT;
            // The chosen chip keeps its face; the others mix towards hover, arriving one stagger apart.
            float lit = kindHovers.get(i).to(hovered && !selected ? 1.0F : 0.0F).eased();
            float arrived = UiMotion.stagger(i, open);
            int background = selected ? UiTheme.selectionSoft()
                    : UiMotion.mix(UiTheme.button(), UiTheme.buttonHover(), lit);
            graphics.fill(x, y, x + typeWidth(), y + TYPE_HEIGHT,
                    UiMotion.fade(background, arrived));

            String label = Component.translatable("screen.howtogo.place_kind." + kinds[i].id())
                    .getString();
            String shown = this.font.plainSubstrByWidth(label, typeWidth() - 2);
            graphics.drawString(this.font, shown,
                    x + Math.max(0, (typeWidth() - this.font.width(shown)) / 2), y + 4,
                    UiMotion.fade(selected ? UiTheme.onSelectionSoft() : UiTheme.textSecondary(),
                            arrived), false);
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
        // Drawn buttons see clicks here, before the kind row, so a press never also changes kind.
        if (button == MouseButton.LEFT) {
            for (PanelButton candidate : buttons) {
                if (candidate.click(mouseX, mouseY)) {
                    return true;
                }
            }
            if (moreButton != null && moreButton.click(mouseX, mouseY)) {
                return true;
            }
        }
        if (isRoadEditor() && button == MouseButton.LEFT && overCategory(mouseX, mouseY)) {
            // Left half back, right half on; consumed so the category is not also a click behind the panel.
            stepCategory(mouseX < detailX() + detailWidth() / 2 ? -1 : 1);
            return true;
        }
        if (isRoadEditor() && button == MouseButton.LEFT && overDirection(mouseX, mouseY)) {
            // Same two halves, stepping the three one-way states; consumed for the same reason.
            stepDirection(mouseX < detailX() + detailWidth() / 2 ? -1 : 1);
            return true;
        }
        if (isPlaceEditor() && button == MouseButton.LEFT) {
            PlaceKind[] kinds = PlaceKind.values();
            for (int i = 0; i < kinds.length; i++) {
                int x = typeX(i);
                int y = typeY();
                if (mouseX >= x && mouseX < x + typeWidth() && mouseY >= y
                        && mouseY < y + TYPE_HEIGHT) {
                    selectedKind = kinds[i];
                    // The old refusal was about the old choice, so it goes with it.
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
    // 1.21.9 made key input a record and moved the key codes to InputConstants; unpack it on entry.
    //? if >=1.21.9 {
    /*public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key();
    *///?} else {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    //?}
        // Enter confirms, so a road can be named and given a storey without the mouse.
        if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
            accept();
            return true;
        }
        // Tab cycles name, storey and number; the category is stepped rather than typed.
        if (keyCode == InputConstants.KEY_TAB && layerField != null) {
            if (getFocused() == field) {
                setFocused(layerField);
            } else if (getFocused() == layerField && numberField != null) {
                setFocused(numberField);
            } else {
                setFocused(field);
            }
            return true;
        }
        // Up and down step the category, the road value with no field: a player typing the storey should not cross the panel. Asked before the focused widget is offered the key.
        if (isRoadEditor()
                && (keyCode == InputConstants.KEY_UP || keyCode == InputConstants.KEY_DOWN)) {
            stepCategory(keyCode == InputConstants.KEY_UP ? -1 : 1);
            return true;
        }
        //? if >=1.21.9 {
        /*return super.keyPressed(event);
        *///?} else {
        return super.keyPressed(keyCode, scanCode, modifiers);
        //?}
    }

    /**
     * The wheel steps the category while the cursor is over its box; only then is it consumed, since a wheel over the rest of the panel belongs to the map behind it.
     */
    @Override
    //? if >=1.20.2 {
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    //?} else {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
    *///?}
        if (isRoadEditor() && scrollY != 0 && overCategory(mouseX, mouseY)) {
            // A wheel up goes back through the class's categories; down goes on.
            stepCategory(scrollY > 0 ? -1 : 1);
            return true;
        }
        if (isRoadEditor() && scrollY != 0 && overDirection(mouseX, mouseY)) {
            // The same reading as the category: up goes back through the three states, down goes on.
            stepDirection(scrollY > 0 ? -1 : 1);
            return true;
        }
        //? if >=1.20.2 {
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        //?} else {
        /*return super.mouseScrolled(mouseX, mouseY, scrollY);
        *///?}
    }

    @Override
    public void onClose() {
        // Cancel takes back an edit that created the thing it edits. Done applies the edit and closes through this same method, which is what committed separates.
        if (!committed && onCancel != null) {
            onCancel.run();
        }
        Screens.set(parent);
    }

    /** No dim, no blur: the map underneath is the context for the name being typed. */
    @Override
    // 1.20.2 added the mouse position and partial tick to this override; both bodies do nothing here.
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The frame's motion first, before the parent re-renders; asked once per frame.
        UiMotion.ScreenState motion = UiMotion.of(this);
        open = motion.opacity();
        if (parent != null) {
            try {
                parent.render(graphics, mouseX, mouseY, partialTick);
            } catch (Throwable t) {
                // The map is only backdrop here; if it cannot be re-rendered we still want the
                // prompt to be usable.
            }
        }
        // The prompt travels as one piece after the parent's pass; translating the backdrop would slide the map.
        UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));

        // A window edge and opaque sheet rather than a tint: the prompt must be readable over whatever the map draws, and the title is what says what is being named.
        graphics.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1,
                UiMotion.fade(UiTheme.windowEdge(), open));
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH,
                UiMotion.fade(UiTheme.panelSolid(), open));
        graphics.drawString(this.font, this.title, panelX + PANEL_PAD, panelY + PANEL_PAD,
                UiMotion.fade(UiTheme.title(), open), false);

        super.render(graphics, mouseX, mouseY, partialTick);
        if (isPlaceEditor()) {
            drawTypes(graphics, mouseX, mouseY);
            if (moreButton != null) {
                // Between the kind row and the error line, with the fields it edits rather than the buttons that close.
                moreButton.render(graphics, this.font, mouseX, mouseY);
            }
        } else if (isRoadEditor()) {
            drawLayerRow(graphics);
            drawCategoryRow(graphics, mouseX, mouseY);
            drawNumberRow(graphics);
            drawDirectionRow(graphics, mouseX, mouseY);
        }
        if (error != null) {
            graphics.drawString(this.font, error.getString(), fieldX, errorY(),
                    UiMotion.fade(UiTheme.error(), open), false);
        }
        // The last row, drawn last so no button can paint over the chips above it.
        drawButtons(graphics, mouseX, mouseY);
        // Last and unconditional: the game's matrix must be handed back unshifted.
        UiMotion.popFrame(graphics);
    }

    /**
     * The done and cancel buttons, drawn rather than widgets, which is why render draws them and mouseClicked routes to them.
     */
    private void drawButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        for (PanelButton button : buttons) {
            button.render(graphics, this.font, mouseX, mouseY);
        }
    }

}
