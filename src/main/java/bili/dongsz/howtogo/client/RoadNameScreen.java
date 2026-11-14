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
 *
 * <h2>Why a road's five values are one panel</h2>
 * A road is named, put on a storey, classified, given a number and told which way it runs in one visit,
 * because they are one answer to "what is this road": the storey says which level of the world it runs
 * on, the category says which kind of highway or street it is, the number is the plate the map prints
 * inside the category's sign, and the direction says whether it is a street or a one-way lane. Splitting
 * the classification into a second screen would mean a player could name a road and never find the other
 * half of it, which is the same reason the storey lives here rather than on a key of its own -- and it is
 * why the one-way state is here too: it used to be reachable only by the {@code O} key on the map, which
 * is a control a player has to be told about, while this panel is where a road is described.
 *
 * <p>The category and the direction are steppers rather than open lists: one class has only a handful of
 * categories, the panel has no room for a list dropped over the map, and stepping is one click either
 * way. Their two halves and the mouse wheel both step them, so a player who has found one of those
 * routes finds the others work too. The number is free text because real roads are not numbered with an
 * integer alone -- see {@link RoadSegment#number()}.
 *
 * <h2>Why the number waits for a category</h2>
 * The number is the plate the map prints inside the category's sign, so a road in its class's built-in
 * "not classified" category has nowhere to put one: {@link RoadSegment#hasSign()} draws no sign for it
 * however much text is stored. The field is therefore disabled -- and the panel says why -- until a real
 * category is chosen, and the text already typed is left in it while the player steps back through the
 * built-in one, so classifying the road again does not make them type the plate twice. The category
 * list itself is the store's ({@link RoadCategoryStore#ofClass}) rather than a fixed set of constants:
 * that is what lets the player add and delete categories without this panel changing, and what makes a
 * road whose category has been deleted open on the built-in one instead of on an id nothing defines
 * (see {@link RoadCategoryStore#normalise}).
 */
public final class RoadNameScreen extends Screen implements ParentBackdrop {

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

    /**
     * What a committed road edit hands back: the name, the storey, the category, the number and the
     * direction.
     *
     * <p>One callback for the whole panel rather than a consumer per row, for the same reason
     * {@link PlaceApply} is one: the five values are a single answer to a single visit, and a caller
     * that had to patch them in separately could save half an edit. The number is null when the
     * chosen category is its class's built-in one -- an unclassified road wears no sign
     * ({@link RoadSegment#hasSign()}), so the panel drops what was typed in the disabled field rather
     * than storing a plate nothing will ever draw. The direction is chain-relative, exactly as
     * {@link bili.dongsz.howtogo.road.RoadEditor#setChainDirection} takes it.
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
    /** Always reserved in the place editor, so the panel does not jump when a refusal appears. */
    private static final int ERROR_HEIGHT = 10;
    /** The place editor's {@code More…} row, under the kind row and above the buttons. */
    private static final int MORE_HEIGHT = 16;
    private static final int MORE_GAP = 3;
    /** The storey's own box: three characters wide is enough for "-32" and keeps the row one line. */
    private static final int LAYER_FIELD_WIDTH = 42;
    /**
     * The room the road editor's category and number rows share on the left, for the word that says
     * what the row is.
     *
     * <p>A column rather than a hint inside each control: the category box is already a swatch and a
     * name, and a text field cannot carry a label of its own at all, so without this the panel would
     * show a coloured box and a field with nothing saying which was which.
     */
    private static final int DETAIL_LABEL_WIDTH = 62;
    /** The gap between a detail row's label and its control. */
    private static final int DETAIL_GAP = 4;
    /** The vertical gap between the road editor's rows under the name. */
    private static final int ROW_GAP = 3;
    /**
     * The category row's height.
     *
     * <p>Shorter than a field because the row is a stepper rather than something typed into: its
     * height only has to hold the swatch and one line of text, and a shorter row leaves that much
     * more of the map visible behind the panel.
     */
    private static final int CATEGORY_HEIGHT = 16;
    /**
     * The longest road number the field takes.
     *
     * <p>Enough for a plate with a letter, a dash and a district suffix -- and for a world that
     * numbers its roads its own way -- while still refusing a paragraph. Only the length is enforced:
     * the number is free text, because what a road is called on its signs is not this mod's to decide.
     */
    private static final int NUMBER_LENGTH = 24;

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
    /**
     * What to do if the panel is cancelled, for an edit that has something to take back.
     *
     * <p>Null for every panel that edits something already there -- cancelling one of those is
     * simply not applying the edit. It is set only by the place editor opened on a place the same
     * press just created: that place exists before the panel does, so its Cancel has to remove it or
     * the map would keep a nameless node the player never asked to keep. The panel does not know how
     * to do that, which is why it is a callback rather than a flag.
     */
    private final Runnable onCancel;
    /**
     * Whether this visit ended in {@code Done}.
     *
     * <p>What keeps {@link #onCancel} from firing after a successful edit: {@code Done} applies the
     * edit and then closes the panel through the same {@code onClose} the Cancel button uses, so the
     * two endings have to be told apart somewhere. A field rather than "was it closed by the button",
     * because Escape and the button are the same cancel.
     */
    private boolean committed;

    // The road editor's extra parts: the storey, the category and the number, given with the name
    // because they are edited together.
    private final int initialLayer;
    private final String initialNumber;
    /**
     * The categories of the edited road's own class, in the order the picker steps through them.
     *
     * <p>Taken once at construction rather than asked of {@link RoadCategoryStore#ofClass} on every
     * step: the class cannot change while the panel is open, and the list is also what says how many
     * steps there are to wrap around. The store's order is kept as given -- the built-in "not
     * classified" category first, then the player's own in the order they were made -- because that is
     * the order every other list of them uses, and the built-in one has to be reachable first, since
     * stepping off the end of the player's categories is how a road is un-classified again.
     */
    private final List<RoadCategory> categories;
    private final RoadApply onAcceptRoad;

    /**
     * The category chosen so far, which is the road's own when the panel opens.
     *
     * <p>The road's own <em>after normalising</em>: a road carrying the id of a category the player
     * has deleted opens on the class's built-in category, which is how every other reader draws such
     * a road (see {@link RoadCategoryStore#normalise}). It is also what the number field's enabled
     * state is read from, so the two can never disagree about whether the road is classified.
     *
     * <p>A field rather than only the widget's state, because the picker is drawn and hit-tested by
     * hand rather than handed to the widget list -- and because it has to survive {@code init}
     * running again on a window resize, when every widget is rebuilt.
     */
    private RoadCategory selectedCategory;

    /**
     * The direction the edited road runs, which is the chain's own direction rather than the piece's.
     *
     * <p>Asked of the chain when the panel opens ({@link RoadEditSession#promptRoadName} passes it in),
     * because a bent street is several segments and the piece under the cursor says nothing about the
     * street: a player looking at one bend of a one-way road has to see the road's answer. A field
     * rather than only a widget, because this row is drawn and hit-tested by hand and the choice has to
     * survive {@code init} running again on a window resize.
     */
    private RoadDirection selectedDirection;

    /**
     * One hover slide per half of the direction row's box, for the same reason the category row keeps
     * one per half: the two halves step opposite ways, and a single face lighting up would say "this is
     * clickable" without saying which way it goes.
     */
    private final UiMotion.Slides directionHovers = new UiMotion.Slides();

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
    /** The number box, present only in the road editor: the road's plate, free text. */
    private EditBox numberField;
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

    /** The opacity the prompt is drawn at this frame; the row helpers read it. See {@link #render}. */
    private float open = 1.0F;

    /**
     * One hover slide per kind chip, so the chip under the cursor is a chip lighting up rather than
     * one being replaced. Keyed by the position in the row, and a field, so a chip redrawn sixty
     * times a second costs nothing and a row of four chips that is swept across never allocates.
     */
    private final UiMotion.Slides kindHovers = new UiMotion.Slides();

    /**
     * One hover slide per half of the road editor's category box.
     *
     * <p>Two halves rather than one face, because the two halves do different things: the left one
     * steps back through the class's categories and the right one steps forward. A single face
     * lighting up under the cursor would say "this is clickable" without saying which way it goes.
     */
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
     * A road: its name, storey, category, number and direction, edited as one thing.
     *
     * <p>Together because they are one gesture -- point at a road, say what it is -- and because the
     * storey and the category are invisible on a map that cannot show height or a classification on
     * its own: a player who had to remember a second key for either would name the road and never find
     * the rest. The storey field is checked before anything is applied, so a mistyped level leaves the
     * panel up with the reason on it rather than closing on a change that did not happen.
     *
     * <p>The category arrives as a value rather than as the road's class, because the category is the
     * whole classification the panel needs: it says which class it belongs to, and that class is what
     * the picker is bounded by. The list is read from {@link RoadCategoryStore} in the constructor, so
     * a category the panel was not given a class for cannot be reached by stepping. The value is
     * normalised first: a road may carry the id of a category the player has since deleted, and what
     * such a road looks like is the class's built-in category -- which is also where the panel has to
     * open, or the first step would compare itself against a value the picker does not hold.
     *
     * @param initialLayer     the storey the road is on now, which the field starts at
     * @param initialCategory  the category the road carries now; null and a category the store no
     *                         longer defines both open the panel on the class's built-in category
     * @param initialNumber    the number the road carries now, or null when it has none
     * @param initialDirection the way the whole road runs now, as the chain's own direction -- see
     *                         {@link bili.dongsz.howtogo.road.RoadEditor#chainDirection}
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
        // Normalised before anything reads it: a null category cannot say which class the road is, and
        // a category the store does not define is drawn as the built-in one everywhere else, so the
        // panel starts on the same value the map would. The built-in class when even the class is
        // missing is the store's fallback, which keeps the picker steppable rather than empty.
        RoadCategory category = RoadCategoryStore.normalise(initialCategory);
        this.initialNumber = initialNumber;
        this.initialLayer = initialLayer;
        this.selectedDirection = initialDirection == null
                ? RoadDirection.TWO_WAY : initialDirection;
        // The store's own order, the built-in category first: copied so that a category added while
        // the panel is open cannot appear under a step the player is in the middle of.
        this.categories = List.copyOf(RoadCategoryStore.ofClass(category.roadClass()));
        this.selectedCategory = category;
        this.onAcceptRoad = onAcceptRoad;
        this.onCancel = null;
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
        this(parent, titleKey, initialValue, initialKind, kindAllowed, initialDetails, anchorX,
                anchorY, onAcceptPlace, null);
    }

    /**
     * The same, plus what cancelling has to take back.
     *
     * <p>Used only when the place being edited was created by the press that opened this panel: the
     * node is already in the network, so Cancel -- and Escape, which is the same ending -- must remove
     * it. Editing a place that already existed passes null and Cancel does what it always did, which
     * is nothing at all.
     *
     * @param onCancel called when the panel is closed without {@code Done}, or null when there is
     *                 nothing to undo
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
     * The room the rows under the name take: the kind row or the road's storey, category and number
     * rows, the place editor's {@code More…} row, and the error line.
     *
     * <p>One method rather than a condition in each of the places that need the number, because the
     * clamp that keeps the panel on screen and the height the panel is drawn at have to agree about
     * it -- and the place editor's row is what made them disagree when it was added, just as the
     * road's category and number rows would have made them disagree again if each had been counted
     * at its own call site.
     */
    private int extraRowsHeight() {
        if (isPlaceEditor()) {
            return TYPE_HEIGHT + MORE_GAP + MORE_HEIGHT + PANEL_PAD + ERROR_HEIGHT;
        }
        if (isRoadEditor()) {
            // Measured from the real heights rather than from the nominal row height: the storey box
            // and the number box are FIELD_HEIGHT tall, and the category and direction boxes between
            // them are CATEGORY_HEIGHT, so the panel's height is the sum of what is actually drawn.
            return FIELD_HEIGHT + ROW_GAP + CATEGORY_HEIGHT + ROW_GAP + FIELD_HEIGHT
                    + ROW_GAP + CATEGORY_HEIGHT + PANEL_PAD + ERROR_HEIGHT;
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

        // Sit just to the right of the anchor, then keep the panel fully on screen. Both bounds are
        // the panel's own padding away from the edge it would otherwise cross -- the name field is
        // inset from the sheet by PANEL_PAD and the title strip sits PANEL_PAD + TITLE_HEIGHT above
        // it -- and the extra height is part of the bottom bound, or the rows under the name would
        // push the buttons off a short window.
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
            // The typed number survives a re-init, which happens on a window resize: every widget is
            // rebuilt, so the text is read off the old box before it is replaced. The name is kept the
            // same way; everything else about the panel is either a field or fixed at construction,
            // so the rows the player typed a name and a number into do not reset under a resize.
            String typedNumber = numberField == null ? null : numberField.getValue();
            numberField = new EditBox(this.font, detailX(), numberRowY(), detailWidth(),
                    FIELD_HEIGHT, Component.translatable("screen.howtogo.road_number"));
            numberField.setMaxLength(NUMBER_LENGTH);
            numberField.setValue(typedNumber != null ? typedNumber
                    : (initialNumber == null ? "" : initialNumber));
            addRenderableWidget(numberField);
            // The uneditable colour is the theme's faint rank rather than the widget's own grey, so a
            // disabled field reads as "not yet" instead of as an ordinary field drawn dark. Set here
            // because init rebuilds the widget on every resize, and asked after the widget exists
            // because the enabled state is a property of the chosen category.
            numberField.setTextColorUneditable(UiTheme.textFaint());
            updateNumberAvailability();
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
            // All five values go back in one call: they were asked for in one panel, and the caller
            // writes them as one undo step. The number is the typed text under a real category and
            // null under the built-in one: an unclassified road has no plate to print a number on
            // ({@link RoadSegment#hasSign()}), so keeping the text would store a value the map never
            // draws and that would reappear the moment the road was classified again -- after the
            // player had already been told the road has no number.
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
        // Asked before applying. A refusal leaves the panel up with the reason on it, so the player
        // can pick another kind instead of losing the edit and wondering what happened.
        if (kindAllowed != null && !kindAllowed.test(selectedKind)) {
            error = Component.translatable("screen.howtogo.station_needs_road");
            return;
        }
        // The details go with the name and the kind: they are the third part of the same edit, and a
        // caller that saved them itself would make this panel's cancel only two-thirds of a cancel.
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

    /** Where the place editor's {@code More…} row sits: under the kind row, with the panel's own gap. */
    private int moreY() {
        return extraRowY() + TYPE_HEIGHT + MORE_GAP;
    }

    /**
     * Where the category row sits: under the storey box, with the panel's own gap.
     *
     * <p>Measured from the storey box's real height rather than from the nominal row height, because
     * the box is a widget {@value #FIELD_HEIGHT} tall and a row counted any shorter would have the
     * category's face drawn over the bottom two pixels of the storey field.
     */
    private int categoryRowY() {
        return extraRowY() + FIELD_HEIGHT + ROW_GAP;
    }

    /** Where the number box sits: under the category row, above the one-way row. */
    private int numberRowY() {
        return categoryRowY() + CATEGORY_HEIGHT + ROW_GAP;
    }

    /**
     * Where the one-way row sits: the last row above the error line.
     *
     * <p>After the number rather than before the category: the four values above it are what a road is
     * called and where it runs, and the direction is a rule about travel along it -- the last question
     * of the visit, and the one whose two chevrons are the only control here that is not a text field,
     * so it reads best at the foot of the fields.
     */
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

    /**
     * Where the error line sits: under everything the panel has above the buttons.
     *
     * <p>Derived rather than written out, because the place editor gained a row and the road editor
     * gained the category and number rows, and a line measured from the kind row would have been
     * drawn through whichever rows came after it.
     */
    private int errorY() {
        if (isPlaceEditor()) {
            return moreY() + MORE_HEIGHT + 1;
        }
        if (isRoadEditor()) {
            return directionRowY() + CATEGORY_HEIGHT + 1;
        }
        return extraRowY() + TYPE_HEIGHT + 1;
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
     * Steps the category picker by one, wrapping within the road's own class.
     *
     * <p>Wrapping rather than clamping, because the list is short and circular by nature: a player on
     * the last category who wants the first should not have to work out that it is behind them. The
     * list is the class's own ({@link RoadCategoryStore#ofClass}), never every category, because a
     * provincial-highway category on a footpath is not a category for that road at all.
     *
     * <p>Compared by value rather than by identity: a category is (class, id), and the store hands out
     * a fresh value on every read, so the category the panel was opened with is a different object
     * from the one the list holds even when it names the same category. An identity comparison would
     * find nothing and step from the first entry every time, so a picker opened on a classified road
     * would jump to the built-in category on the first press, and then never move forwards.
     */
    private void stepCategory(int delta) {
        if (categories.isEmpty()) {
            return;
        }
        int index = categories.indexOf(selectedCategory);
        if (index < 0) {
            // Defensive: the constructor normalises, so this is only reachable if the list it copied
            // did not contain the value it normalised -- stepping from the built-in category is the
            // least surprising recovery, and the alternative is a picker that cannot move at all.
            index = 0;
        }
        selectedCategory = categories.get(Math.floorMod(index + delta, categories.size()));
        // The number is only usable under a real category, so stepping across the built-in one has to
        // enable and disable it: see updateNumberAvailability.
        updateNumberAvailability();
    }

    /**
     * Makes the number field usable only while the road is classified.
     *
     * <p>An unclassified road has no sign for a number to be printed on, so the field is disabled
     * rather than hidden or cleared: the typed text stays in it, which is what lets a player step back
     * to the built-in category to compare against it and step on again to the number they had written.
     * The disabled face alone is the whole of the explanation -- nothing is written beside it, so the
     * row cannot be mistaken for a refusal the player earned.
     *
     * <p>A no-op before {@link #init} has built the widget, and for every panel that is not the road
     * editor: only the road editor has a number, and it is the only caller that reaches this.
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
     * Steps the one-way state by one: two-way, along the road, against it, two-way.
     *
     * <p>The same three-state cycle the {@code O} key walks, deliberately: the two controls act on the
     * same value, so a player who learned the order from one of them reads the other without a second
     * table of what comes next. The state is chain-relative ({@link RoadDirection#FORWARD} is along the
     * road as the editor walked it), which is what {@link bili.dongsz.howtogo.road.RoadEditor} writes
     * onto every piece, so a bent street stays one street.
     */
    private void stepDirection(int delta) {
        selectedDirection = delta > 0 ? selectedDirection.next()
                : selectedDirection.next().next();
        error = null;
    }

    /**
     * The one-way row: the label, and a box whose two halves step the state.
     *
     * <p>Its own row rather than a third route to the {@code O} key: the key is a map shortcut a player
     * has to be told about, while the panel is where a road is described, and a player who opened this
     * panel to classify a road should not have to close it and learn a key to make the road one-way.
     * The box is the same control the category row uses, so the two rows read as the same kind of thing
     * rather than as a button and a value.
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

        // The direction's own colour rather than a category swatch: there is no marker to print here,
        // and a road that is one-way is drawn with arrows in the map's own ink, so the word is the
        // whole readout. It is centred in the room the two chevrons leave.
        String label = Component.translatable("hud.howtogo.direction." + selectedDirection.id())
                .getString();
        String shown = PlaceText.ellipsis(this.font, label, width - 24 - 11);
        graphics.drawString(this.font, shown,
                x + (width - this.font.width(shown)) / 2, textY,
                UiMotion.fade(UiTheme.text(), open), false);
    }

    /**
     * The small word at the left of one of the road editor's detail rows.
     *
     * <p>Cut to the label column rather than drawn whole: the label is a translated word and a longer
     * translation must not reach into the control beside it -- the name of the row is a label, and a
     * label that runs over its own field is worse than one that ends in an ellipsis.
     */
    private void drawDetailLabel(GuiGraphics graphics, String langKey, int rowY, int rowHeight) {
        String label = PlaceText.ellipsis(this.font, Component.translatable(langKey).getString(),
                DETAIL_LABEL_WIDTH - 4);
        graphics.drawString(this.font, label, fieldX, rowY + (rowHeight - 8) / 2,
                UiMotion.fade(UiTheme.textSecondary(), open), false);
    }

    /**
     * The category row: the label, and a box that is at once the readout and the stepper.
     *
     * <p>A box with two clickable halves rather than a button and a separate value, because the value
     * is the thing the button acts on and putting them together is what makes the row read as one
     * control. Its left half steps back through the class's categories and its right half forward;
     * the two chevrons say which half does which before it is tried, since a box with two halves and
     * no sign on either is only discovered by clicking it.
     *
     * <p>The swatch before the name is the category's colour from {@link RoadCategoryStore}, which is
     * the colour its sign is drawn in on the map: the name alone would tell the player which word they
     * picked, and the colour tells them which sign they picked.
     */
    private void drawCategoryRow(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = categoryRowY();
        drawDetailLabel(graphics, "screen.howtogo.road_category", y, CATEGORY_HEIGHT);

        int x = detailX();
        int width = detailWidth();
        boolean over = overCategory(mouseX, mouseY);
        boolean overLeft = over && mouseX < x + width / 2;
        boolean overRight = over && mouseX >= x + width / 2;
        // One slide per half, so a sweep across the box lights the half under the cursor rather than
        // the whole face: the two halves are the two directions of the step.
        float litLeft = categoryHovers.get(0).to(overLeft ? 1.0F : 0.0F).eased();
        float litRight = categoryHovers.get(1).to(overRight ? 1.0F : 0.0F).eased();
        int face = UiMotion.mix(UiTheme.button(), UiTheme.buttonHover(),
                Math.max(litLeft, litRight));
        HudDraw.fillRounded(graphics, x, y, x + width, y + CATEGORY_HEIGHT, 3,
                UiMotion.fade(face, open));

        int textY = y + (CATEGORY_HEIGHT - 8) / 2;
        // The chevrons are mixed from the faint text to the secondary one by their own half's slide:
        // the side about to be clicked is the side that darkens.
        graphics.drawString(this.font, "<", x + 3, textY,
                UiMotion.fade(UiMotion.mix(UiTheme.textFaint(), UiTheme.textSecondary(), litLeft),
                        open), false);
        graphics.drawString(this.font, ">", x + width - 3 - this.font.width(">"), textY,
                UiMotion.fade(UiMotion.mix(UiTheme.textFaint(), UiTheme.textSecondary(), litRight),
                        open), false);

        // The marker first and the name after it: the swatch is the fact, the name is the reading of
        // it, and the name is cut to the room the marker and the right chevron leave.
        HudDraw.drawRowMarker(graphics, x + 12, y + (CATEGORY_HEIGHT - 9) / 2, 9,
                UiMotion.fade(RoadCategoryStore.color(selectedCategory), open));
        String name = RoadCategoryStore.name(selectedCategory);
        graphics.drawString(this.font, PlaceText.ellipsis(this.font, name, width - 24 - 13),
                x + 24, textY, UiMotion.fade(UiTheme.text(), open), false);
    }

    /**
     * The number row: its label.
     *
     * <p>The field itself is a real widget and draws itself, which is why it is an {@link EditBox} at
     * all -- that is what gives the row the platform input method for the characters a road number
     * may hold.
     *
     * <p>Nothing is written beside a disabled field: an unclassified road simply has no number to
     * give, and the greyed box is the whole of that statement. A line of explanation under it read as
     * a refusal or an error when the player had not done anything wrong.
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
            // The chosen chip keeps its own face whatever the cursor does -- it is the answer, not a
            // button -- and every other one is mixed towards the hover face by its own slide. The
            // chips also arrive one stagger step apart, so the row is read left to right.
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
        // The done and cancel buttons are drawn, not widgets, so this is their only chance to see a
        // click; it comes before the kind row so a press on one of them is never also a change of
        // kind under it.
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
            // Left half back, right half on: the same direction the two chevrons on the box point.
            // A press that landed on the box is consumed here, before the widgets are offered it, so
            // the category cannot also be read as a click into whatever is behind the panel.
            stepCategory(mouseX < detailX() + detailWidth() / 2 ? -1 : 1);
            return true;
        }
        if (isRoadEditor() && button == MouseButton.LEFT && overDirection(mouseX, mouseY)) {
            // The same two halves, stepping the three one-way states: a press on this row is consumed
            // before the widgets are offered it, for the same reason as the category row.
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
        // Tab cycles the road editor's three fields: name, then storey, then number. A plain Screen has
        // no tab order of its own, so without this the storey and number boxes could only be reached
        // with the mouse -- the wrong half of the panel to make the player reach for, and the one an
        // input method does not help with either. The category is deliberately not in the cycle: it is
        // stepped with the arrows rather than typed into, so a focus of its own would be a state the
        // player could tab to and find nothing to type in.
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
        // Up and down step the category, the one road value with no field to click into: a player who
        // is already typing the storey should not have to cross the panel with the mouse for the
        // classification. Asked before the focused widget is offered the key, so the arrows cannot be
        // swallowed by a box instead. Up is the way back through the class's categories and down the
        // way on -- the same reading as the box's two halves and as a wheel turning up or down.
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
     * The wheel steps the category while the cursor is over its box.
     *
     * <p>The wheel is the third route to the same control -- beside the box's two halves and the up
     * and down keys -- because a player who has found one of them should not find the others inert.
     * Only a wheel turned over the box is consumed: a wheel over the rest of the panel belongs to the
     * map behind it, and swallowing it would stop a player zooming while a prompt merely passes under
     * the cursor.
     */
    @Override
    //? if >=1.20.2 {
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    //?} else {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
    *///?}
        if (isRoadEditor() && scrollY != 0 && overCategory(mouseX, mouseY)) {
            // A wheel turned up goes back through the class's categories, the way scrolling up moves
            // back through a list anywhere else; down goes on.
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
        // An edit that created the thing it edits is taken back by cancelling it. Done applies the
        // edit and then closes through this same method, which is what `committed` separates: without
        // it, a successful place edit would delete the place it had just written.
        if (!committed && onCancel != null) {
            onCancel.run();
        }
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
        // The frame's motion first, before the parent is re-rendered and before the sheet is drawn:
        // everything this prompt paints reads this one value, and UiMotion.of is also what pumps a
        // deferred screen swap, so it is asked exactly once per frame.
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
        // The prompt then travels as one piece -- edge, sheet, fields, chips and buttons -- so it
        // rises into place rather than being cut on. Pushed after the parent's pass rather than
        // before it: that backdrop is another screen's frame, and a translated one would slide the
        // map under the prompt and leave a strip of the parent's own dimming along its edge.
        UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));

        // A window edge and an opaque sheet rather than a tint over the map: the prompt has to be
        // readable on top of whatever the map happens to be drawing under it, and the title is the
        // first thing on it that says what is being named. Both fade in with the prompt, so it
        // arrives over the map instead of appearing on it.
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
                // Between the kind row and the error line, and before the buttons: it is an action on
                // the place rather than one of the two that close the panel, so it sits with the
                // fields it edits.
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
        // The panel's last row, drawn last: the kind row and the error line above it are this class's
        // own drawing, and a button painted before its own row of chips could have one across it.
        drawButtons(graphics, mouseX, mouseY);
        // Last, and unconditional: everything above was drawn inside the displacement pushed at the
        // top of this method, and the game's matrix has to be handed back unshifted.
        UiMotion.popFrame(graphics);
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
