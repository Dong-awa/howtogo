package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadCategory;
import bili.dongsz.howtogo.road.RoadClass;
import bili.dongsz.howtogo.road.RoadSegment;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
// 1.21.9 bundled mouse coordinates and the button into a record.
//? if >=1.21.9 {
/*import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
*///?}
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The editor behind the road-label setting: every road category's name and colour, in one window.
 *
 * <h2>What a category is, and what a player owns of it</h2>
 * A road's {@link RoadClass} says how it is drawn -- its colour and width; its {@link RoadCategory}
 * is the classification one level below that, the number plate a road wears on the map: a national
 * highway against a provincial one, a trunk street against an alley, a trunk line against a branch.
 * Since categories became the player's to define (see {@link RoadCategoryStore}), this window is
 * where that definition is kept: the set of categories, their names, and the colour of their sign.
 *
 * <p>Each class has exactly one category that is not the player's -- the built-in "not classified"
 * one. It is drawn here because a player has to see what an unclassified road is, but it has no
 * name box, no delete button and no picker: it is the answer every unclassified road gives, so
 * letting it be renamed or removed would leave those roads with nothing to say. Everything else in
 * this window is created, renamed, recoloured and deleted here.
 *
 * <h2>Names are a draft; colours and the set are not</h2>
 * A colour is a press: the swatch opens {@link RoadCategoryColorScreen}, and the picker's Done writes
 * the colour to the store at once, which is why there is no undo for a colour. Adding and deleting a
 * category are also immediate -- the store writes a new definition the moment the button is pressed,
 * and a delete takes the category off the roads that wear it (see {@link #deleteCategory}). A name is
 * text, and text is not a value until the player has finished typing it: every box is read once on
 * the way out. {@code Done} keeps what was typed, {@code Cancel} is the one ending that drops it, and
 * Escape saves like {@code Done} does -- leaving this page is not the same gesture as pressing the
 * button that says the edit is unwanted. A blank box means "the store's own default name", not "no
 * name", because a sign on the map has to print something.
 *
 * <h2>Deleting is a two-click gesture</h2>
 * A category is not a row of a table: deleting one takes it off every road that carries it, and the
 * store cannot bring it back (a deleted id is never reused). So the wide {@code ×} on the row arms
 * itself on the first press and deletes on the second, and the hint under the list says which
 * category is armed and what deletion will do to it. Any other press takes it out of that state, so
 * the confirm can never be a press the player made about something else.
 *
 * <h2>Why the list scrolls by whole bands, not by pixels</h2>
 * The name boxes are real {@link EditBox} widgets, drawn by the game's own widget pass after
 * everything this screen paints. A widget cannot be clipped to a half-visible row: it would either
 * hang over the hairline above the list or be cut off by nothing at all. So the content is laid out
 * as whole bands -- six class headers, one band per category and one "add" band per class -- and the
 * wheel scrolls by band. Every band that is drawn is drawn complete, its box included, and a band
 * outside the window takes its box with it. That is also why the screen never scrolls smoothly.
 *
 * <h2>Why the window is painted from render, not renderBackground</h2>
 * The order this screen needs is: the sheet, then the bands and their swatches, then the boxes on
 * top of their rows. {@code Screen.render} paints the background first and the widget list after it,
 * which is exactly that order -- but only if the sheet is painted before the widget pass, and on
 * 1.20.1 the background is never requested by the game at all. Painting the sheet at the top of
 * {@link #render} and asking for the widget pass at the end gives both eras the same order, which is
 * why {@link #renderBackground} is deliberately empty (it must also stay overridden, or the vanilla
 * blur and wash would be drawn under this screen's own window).
 */
final class RoadCategoryScreen extends Screen {

    private static final int PAD = 10;
    private static final int GAP = 6;
    private static final int TITLE_HEIGHT = 16;
    private static final int LINE_HEIGHT = 10;
    private static final int BUTTON_HEIGHT = 18;
    /** A group header's band: a word and the air around it, no control of its own. */
    private static final int HEADER_HEIGHT = 18;
    /** A category's band: one swatch, one name box and the row's two buttons. */
    private static final int ROW_HEIGHT = 24;
    /** The "add category" band at the end of a group: one button and the air around it. */
    private static final int ADD_HEIGHT = 20;
    private static final int FIELD_HEIGHT = 16;
    /** The height the delete and add buttons take inside their bands. */
    private static final int ROW_BUTTON_HEIGHT = 16;
    /** The swatch's side, as the class comment asks: small enough to be a marker, not a block. */
    private static final int SWATCH = 14;
    private static final int SWATCH_X = 8;
    private static final int FIELD_GAP = 8;
    /**
     * How wide the delete button is.
     *
     * <p>Wide enough to carry the confirming word instead of the cross without the label being cut
     * to an ellipsis: the armed label is what tells the player the second press is the one that acts,
     * and a label clipped to "…" tells them nothing.
     */
    private static final int DELETE_WIDTH = 34;
    /** How wide the "add category" button is; it stops growing with the window. */
    private static final int ADD_WIDTH = 150;
    /** One band per wheel notch: a list of categories is read, not skimmed. */
    private static final int SCROLL_STEP = 1;
    /**
     * A category name is drawn on a sign card on the map, so it is a label rather than a sentence;
     * the same budget the road-name field takes is enough for one and stops a paste of prose.
     */
    private static final int MAX_NAME_LENGTH = 48;
    /**
     * The store's own name for a category nobody has named yet.
     *
     * <p>Repeated here rather than exposed by the store because it is not a value callers read, it
     * is the field's hint and the thing a blank box is compared against: {@code RoadCategoryStore}
     * falls back to this translation whenever a definition's name is blank, so the editor has to
     * know it to tell "the player typed the default" from "the player typed nothing".
     */
    private static final String NAME_PLACEHOLDER_KEY = "howtogo.road_category.new";

    /** The screen to go back to: the settings screen this was opened from. */
    private final Screen parent;

    /**
     * Every band of the list in drawing order, rebuilt when the set of categories changes.
     *
     * <p>Unlike the old fixed-enum list this one is not built once: adding or deleting a category
     * changes it, and the offsets it carries are what the scroll, the drawing, the boxes and the row
     * buttons all read. Rebuilding it and re-running the layout is therefore one gesture -- see
     * {@link #rebuildWidgets()}, which the game itself uses for the same reason on a resize.
     */
    private final List<Item> items = new ArrayList<>();

    /** The name box of each player-made category, live from {@link #init()} until the screen closes. */
    private final Map<RoadCategory, EditBox> fields = new LinkedHashMap<>();

    /**
     * Names typed so far, lifted out of the boxes before they are rebuilt.
     *
     * <p>{@code init()} runs again when the window is resized and when this screen comes back from
     * the colour picker, and it rebuilds the widgets with it. A name typed but not yet committed
     * exists only in the old box, so it has to be carried across by hand or a round trip would
     * silently restore the stored names under the player's cursor.
     */
    private final Map<RoadCategory, String> drafts = new LinkedHashMap<>();

    /**
     * The buttons that live on the list -- the delete cross of each row and the add button of each
     * group -- rebuilt whenever the rows move.
     *
     * <p>Unlike the footer they cannot be built once: {@link PanelButton} is a fixed rectangle, and
     * these travel with the bands. They are therefore rebuilt on a scroll notch, on a set change and
     * on a resize, which is cheap at this list's size and keeps one rule instead of two for where a
     * control is.
     */
    private List<PanelButton> rowButtons = List.of();

    private List<PanelButton> footerButtons = List.of();

    /** One hover slide per band that owns a swatch, so the ring lights instead of switching. */
    private final UiMotion.Slides swatchHovers = new UiMotion.Slides();

    /**
     * The category whose delete button is armed, or null.
     *
     * <p>The two-press confirm is state of the screen rather than of the button because it has to
     * survive the button being rebuilt: a scroll notch rebuilds every row button, and an armed row
     * that came back un-armed after the wheel was touched would be worse than no confirm at all.
     */
    private RoadCategory armedDelete;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int fieldX;
    private int fieldWidth;
    private int deleteX;
    private int hintY;
    private int contentHeight;
    /** Whether the window is tall enough for the hint line under the list; see {@link #init()}. */
    private boolean hintVisible;

    /**
     * The first band drawn: an index into {@link #items}, not a pixel offset.
     *
     * <p>The whole list scrolls by band -- see the class note -- so its position is one integer
     * naming the band at the top of the window. No smoothing is applied for the same reason: every
     * band that is drawn has to be whole, and a travelling value would put a band part-way out.
     */
    private int scroll;

    /**
     * Whether this visit ended with the footer's Cancel.
     *
     * <p>Needed because Cancel is not the only way out: Escape goes through {@link #onClose}, which
     * saves. Without this flag a Cancel pressed a moment before Escape -- while the screen is still
     * drawing its exit -- would be undone by the close that follows it, and the one ending that
     * promises to discard the text would quietly keep it.
     */
    private boolean cancelled;

    /** The opacity the whole window is drawn at this frame; see {@link #render}. */
    private float open = 1.0F;

    RoadCategoryScreen(Screen parent) {
        super(Component.translatable("screen.howtogo.categories.title"));
        this.parent = parent;
    }

    /** Not a pause screen: the world keeps running behind this window, like every other one. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ content

    /**
     * Lays the list out in content coordinates: a header per class, its categories, then its add
     * button.
     *
     * <p>The order is the declaration order of the classes, which is the order a player expects to
     * read a road network in -- highways first, then streets, paths, rail, water and ice. Inside a
     * group the store answers with the built-in category first and the player-made ones in the order
     * they were made (see {@link RoadCategoryStore#ofClass}), so a category keeps its place when a
     * neighbour is deleted or renamed.
     */
    private void rebuildItems() {
        items.clear();
        for (RoadClass roadClass : RoadClass.values()) {
            items.add(Item.header(roadClass));
            for (RoadCategory category : RoadCategoryStore.ofClass(roadClass)) {
                items.add(category.isNone()
                        ? Item.builtIn(roadClass, category) : Item.category(roadClass, category));
            }
            items.add(Item.addButton(roadClass));
        }
        int y = 0;
        for (Item item : items) {
            item.offset = y;
            y += item.height;
        }
        contentHeight = y;
    }

    // ------------------------------------------------------------------- layout

    @Override
    protected void init() {
        panelW = Math.min(480, this.width - 16);
        panelH = Math.min(400, this.height - 16);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int footerY = panelY + panelH - PAD - BUTTON_HEIGHT;
        hintY = footerY - GAP - LINE_HEIGHT;
        listX = panelX + PAD;
        listW = panelW - PAD * 2;
        listY = panelY + PAD + TITLE_HEIGHT + 8;
        // A window shorter than the list plus its two bands cannot hold the hint as well; the list
        // keeps its room and the hint is omitted rather than painted through the last row.
        hintVisible = hintY - GAP - 4 - listY >= ROW_HEIGHT;
        listH = Math.max(ROW_HEIGHT, hintY - GAP - 4 - listY);
        fieldX = listX + SWATCH_X + SWATCH + FIELD_GAP;
        deleteX = listX + listW - 8 - DELETE_WIDTH;
        fieldWidth = Math.max(40, deleteX - GAP - fieldX);

        // The set may have changed since this screen was built -- a category added a moment ago, or
        // a hand-edited file the store has only just read -- so the bands are re-measured here rather
        // than in the constructor. init() also runs again on a resize and on the way back from the
        // colour picker, which is the same reason the boxes are rebuilt below.
        rebuildItems();
        scroll = clampScroll(scroll);
        buildFields();
        buildRowButtons();
        buildFooterButtons(footerY);
        layoutFields();
    }

    /**
     * Builds one name box per player-made category, carrying the typed text across a rebuild.
     *
     * <p>The seed is the draft if one exists, and the store's name otherwise -- which is what makes a
     * box show the name the sign will carry rather than an empty field on first open. A cleared box
     * keeps the store's default, which the hint states.
     *
     * <p>The built-in category has no box: it cannot be renamed, so a box would be a control that
     * silently refuses the edit.
     */
    private void buildFields() {
        liftDrafts();
        for (Item item : items) {
            if (item.kind != Kind.CATEGORY) {
                continue;
            }
            RoadCategory category = item.category;
            String typed = drafts.get(category);
            String seed = typed != null ? typed : RoadCategoryStore.name(category);
            EditBox field = new EditBox(this.font, fieldX, 0, fieldWidth, FIELD_HEIGHT,
                    Component.translatable("screen.howtogo.categories.name"));
            field.setMaxLength(MAX_NAME_LENGTH);
            field.setHint(Component.translatable(NAME_PLACEHOLDER_KEY));
            field.setValue(seed == null ? "" : seed);
            addRenderableWidget(field);
            fields.put(category, field);
        }
    }

    /**
     * Reads every live box into the drafts and forgets the boxes.
     *
     * <p>Called before a rebuild, so a name typed but not yet committed is not lost when the widgets
     * are thrown away. A box whose category has since been deleted is skipped rather than carried:
     * the draft would otherwise outlive the row and come back if the same id were ever handed out
     * again, which the store promises never happens.
     */
    private void liftDrafts() {
        for (Map.Entry<RoadCategory, EditBox> entry : fields.entrySet()) {
            if (RoadCategoryStore.has(entry.getKey())) {
                drafts.put(entry.getKey(), entry.getValue().getValue());
            }
        }
        fields.clear();
    }

    /**
     * Puts every name box where its band is drawn this frame, and hides the ones out of the window.
     *
     * <p>The boxes are widgets, so they cannot be clipped to the list; a box for a band that is not
     * fully inside would be drawn over the hairline above the list or the hint below it. The list
     * scrolls by whole bands precisely so that this test has an answer.
     */
    private void layoutFields() {
        if (items.isEmpty()) {
            return;
        }
        int top = items.get(scroll).offset;
        for (Item item : items) {
            if (item.kind != Kind.CATEGORY) {
                continue;
            }
            EditBox field = fields.get(item.category);
            if (field == null) {
                continue;
            }
            int bandY = listY + item.offset - top;
            boolean visible = bandVisible(bandY, item);
            if (visible) {
                field.setY(bandY + (ROW_HEIGHT - FIELD_HEIGHT) / 2);
            }
            field.setVisible(visible);
            if (!visible && getFocused() == field) {
                // A box scrolled out of the window is not left holding the keyboard: its caret would
                // keep taking characters for a row the player cannot see.
                setFocused(null);
            }
        }
    }

    /**
     * Builds the delete cross of every visible row and the add button of every visible group.
     *
     * <p>Rebuilt rather than moved because a {@link PanelButton} is a fixed rectangle; the alternative
     * would be a second, scroll-aware button class for the same three states. The armed category is
     * read here, so the button that is armed is built with the confirming label and the danger face.
     */
    private void buildRowButtons() {
        List<PanelButton> built = new ArrayList<>();
        if (items.isEmpty()) {
            rowButtons = built;
            return;
        }
        int top = items.get(scroll).offset;
        for (Item item : items) {
            int bandY = listY + item.offset - top;
            if (!bandVisible(bandY, item)) {
                continue;
            }
            if (item.kind == Kind.CATEGORY) {
                RoadCategory category = item.category;
                boolean armed = category.equals(armedDelete);
                built.add(new PanelButton(deleteX, bandY + (ROW_HEIGHT - ROW_BUTTON_HEIGHT) / 2,
                        DELETE_WIDTH, ROW_BUTTON_HEIGHT,
                        Component.translatable(armed
                                ? "screen.howtogo.categories.delete_confirm"
                                : "screen.howtogo.categories.delete"),
                        armed ? PanelButton.Kind.DANGER : PanelButton.Kind.NEUTRAL,
                        () -> deleteClicked(category)));
            } else if (item.kind == Kind.ADD) {
                built.add(new PanelButton(fieldX, bandY + (ADD_HEIGHT - ROW_BUTTON_HEIGHT) / 2,
                        Math.min(fieldWidth, ADD_WIDTH), ROW_BUTTON_HEIGHT,
                        Component.translatable("screen.howtogo.categories.add"),
                        PanelButton.Kind.NEUTRAL, () -> addCategory(item.roadClass)));
            }
        }
        rowButtons = built;
    }

    private void buildFooterButtons(int footerY) {
        int inner = panelW - PAD * 2;
        int half = (inner - GAP) / 2;
        // Done is the one action that keeps the names, so it takes the accent; Cancel is the way out
        // that drops them and is an ordinary button. Both are this mod's own buttons rather than the
        // game's grey widgets, like every other screen here.
        footerButtons = List.of(
                new PanelButton(panelX + PAD, footerY, half, BUTTON_HEIGHT,
                        Component.translatable("gui.done"), PanelButton.Kind.PRIMARY, this::done),
                new PanelButton(panelX + PAD + half + GAP, footerY, inner - half - GAP,
                        BUTTON_HEIGHT, Component.translatable("gui.cancel"),
                        PanelButton.Kind.NEUTRAL, this::cancel));
    }

    // -------------------------------------------------------------------- input

    @Override
    //? if >=1.21.9 {
    /*public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
    *///?} else {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //?}
        if (button == MouseButton.LEFT) {
            for (PanelButton candidate : footerButtons) {
                if (candidate.click(mouseX, mouseY)) {
                    return true;
                }
            }
            // The row buttons come next, and a snapshot is taken because a press on one of them
            // rebuilds the list it lives in -- the delete button arms itself and the whole group's
            // buttons are built again. Iterating the live field would then answer a press with a
            // button that no longer exists.
            List<PanelButton> rowSnapshot = rowButtons;
            for (PanelButton candidate : rowSnapshot) {
                if (candidate.click(mouseX, mouseY)) {
                    return true;
                }
            }
            RoadCategory swatch = categorySwatchAt(mouseX, mouseY);
            if (swatch != null) {
                // A press on a colour square is a statement about the colour: the picker opens over
                // this window, and a press that landed on the swatch is consumed here so it is never
                // also a press on the box beside it.
                armedDelete = null;
                openPicker(swatch);
                return true;
            }
            // Any other press takes the delete confirm out of its armed state. The press itself is
            // still offered to the widgets below -- the point of the disarm is that the *next*
            // delete press must be a fresh one, not that clicking a name box should be swallowed.
            if (armedDelete != null) {
                armedDelete = null;
                buildRowButtons();
            }
        } else if (armedDelete != null) {
            // The right and middle buttons have nothing else to do here, but a press of one of them
            // is still "any other press": the confirm is about a deliberate second press, and a
            // gesture that is not one has to take it back.
            armedDelete = null;
            buildRowButtons();
        }
        //? if >=1.21.9 {
        /*return super.mouseClicked(new MouseButtonEvent(mouseX, mouseY,
                new MouseButtonInfo(button, 0)), false);
        *///?} else {
        return super.mouseClicked(mouseX, mouseY, button);
        //?}
    }

    @Override
    //? if >=1.21.9 {
    /*public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key();
    *///?} else {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    //?}
        // Enter is Done. The boxes are a form, and a player who has just typed a name should not have
        // to reach for the mouse to keep it; Escape still closes (and saves) through the game's own
        // handler, which is why neither key is consumed on the way to it.
        if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
            done();
            return true;
        }
        // Tab walks the name boxes that are on screen, in the order they are drawn. The game's own
        // cycle would also reach the boxes scrolled out of the window, and focusing one of those
        // would take the keyboard to a row the player cannot see.
        if (keyCode == InputConstants.KEY_TAB) {
            focusNextField();
            return true;
        }
        //? if >=1.21.9 {
        /*return super.keyPressed(event);
        *///?} else {
        return super.keyPressed(keyCode, scanCode, modifiers);
        //?}
    }

    /**
     * Moves the focus to the next visible box, wrapping at the end.
     *
     * <p>Visible boxes in the list's order, not in the widget list's: the list's order is the order
     * the bands are drawn in, so Tab reads down the window rather than through the order the boxes
     * happened to be added in.
     */
    private void focusNextField() {
        List<EditBox> visible = new ArrayList<>();
        for (EditBox field : fields.values()) {
            if (field.visible) {
                visible.add(field);
            }
        }
        if (visible.isEmpty()) {
            return;
        }
        int current = visible.indexOf(getFocused());
        setFocused(visible.get((current + 1) % visible.size()));
    }

    @Override
    // 1.20.2 split the wheel delta into a horizontal and a vertical component; 1.20.1 passes one.
    // Every use in this mod reads the vertical one, so only the signature and the super call differ.
    //? if >=1.20.2 {
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    //?} else {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
    *///?}
        if (insideList(mouseX, mouseY) && scrollY != 0) {
            int next = clampScroll(scroll - (int) Math.signum(scrollY) * SCROLL_STEP);
            if (next != scroll) {
                scroll = next;
                // The row buttons are fixed rectangles, so they have to be rebuilt where the bands
                // have moved to; the boxes are moved by the next frame's layout pass.
                buildRowButtons();
                return true;
            }
        }
        //? if >=1.20.2 {
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        //?} else {
        /*return super.mouseScrolled(mouseX, mouseY, scrollY);
        *///?}
    }

    // ------------------------------------------------------------------ actions

    /** Done: commit every typed name, then leave. */
    private void done() {
        saveNames();
        Screens.set(parent);
    }

    /**
     * Cancel: leave without committing the names.
     *
     * <p>The colours and the set of categories are not undone, and cannot be from here: a colour is
     * written by the picker's Done and a category is created or deleted by the press that asked for
     * it. The hint says so, and the two buttons therefore differ in exactly one thing -- whether the
     * text in the boxes is kept.
     */
    private void cancel() {
        cancelled = true;
        Screens.set(parent);
    }

    @Override
    public void onClose() {
        // Escape is a close, not this screen's Cancel: the names typed so far are what the player
        // came here to write, so leaving the page keeps them. Cancel is the one ending that discards,
        // and `cancelled` is what keeps a close racing that press from saving over it.
        if (!cancelled) {
            saveNames();
        }
        Screens.set(parent);
    }

    /**
     * Writes each box's text to the store; a blank line restores the store's default name.
     *
     * <p>A box whose text is exactly what the store already answers with is left alone, unless that
     * text is the store's placeholder. That is not an optimisation: a box starts out holding the
     * <em>displayed</em> name, so writing every box back unconditionally would turn "opened this page
     * and pressed Done" into a store full of literal names frozen in whatever language the page was
     * opened in. A new category is exactly that case: the store names it with the translated
     * placeholder when it is created, and writing the placeholder back as a name would stop it
     * following the language. So the placeholder is written as a blank, which makes the store
     * translate it again on every read -- and anything else the player actually typed is written as
     * typed.
     */
    private void saveNames() {
        String placeholder = Component.translatable(NAME_PLACEHOLDER_KEY).getString();
        for (Map.Entry<RoadCategory, EditBox> entry : fields.entrySet()) {
            RoadCategory category = entry.getKey();
            if (!RoadCategoryStore.has(category)) {
                continue;
            }
            String typed = entry.getValue().getValue().trim();
            boolean blank = typed.isBlank();
            if (!blank && !typed.equals(placeholder) && typed.equals(RoadCategoryStore.name(category))) {
                continue;
            }
            RoadCategoryStore.setName(category, blank || typed.equals(placeholder) ? "" : typed);
        }
    }

    /**
     * Makes a category for a class, then re-lays the rows so its band appears.
     *
     * <p>The store has already written the new definition by the time this returns (see
     * {@link RoadCategoryStore#add}); what is left is the window. A re-init is the whole of it --
     * the bands, the boxes and the buttons all read the set -- and the typed names survive it
     * because {@link #liftDrafts} reads them out of the boxes before they are thrown away.
     */
    private void addCategory(RoadClass roadClass) {
        RoadCategory created = RoadCategoryStore.add(roadClass);
        armedDelete = null;
        rebuildWidgets();
        // A new band at the bottom of a group can be below the window, and a button that appears to
        // do nothing is worse than one that scrolls the list to answer. The scroll is moved by whole
        // bands to where the new row fits.
        reveal(created);
    }

    /** First press arms the row's delete, the second one deletes; anything else is handled elsewhere. */
    private void deleteClicked(RoadCategory category) {
        if (category.equals(armedDelete)) {
            deleteCategory(category);
            return;
        }
        armedDelete = category;
        buildRowButtons();
    }

    /**
     * Deletes a category and puts the roads that wear it back to "not classified".
     *
     * <p>The store deletes a definition, not the roads that carry its id -- the network is not its
     * business (see {@link RoadCategoryStore#remove}). Left alone, those roads would keep a dangling
     * reference that every reader draws as unclassified but that would come back to life on the day
     * an id was reused. So they are reclassified here, which is also what the store's own tolerance
     * says such a reference means, and the network is marked dirty so the cleanup reaches the save
     * file with the player's next autosave rather than never.
     */
    private void deleteCategory(RoadCategory category) {
        armedDelete = null;
        if (!RoadCategoryStore.remove(category)) {
            // Nothing was deleted -- the store no longer had it, which a hand-edited file can
            // arrange. Re-laying the rows is still right: they describe a set that has changed.
            rebuildWidgets();
            return;
        }
        boolean roadsChanged = false;
        for (RoadSegment segment : RoadStore.get().segments()) {
            if (category.equals(segment.category())) {
                segment.setCategory(RoadCategory.none(segment.roadClass()));
                roadsChanged = true;
            }
        }
        if (roadsChanged) {
            RoadStore.markDirty();
        }
        // The draft goes with the row: a name typed into a box that is about to be thrown away must
        // not survive in the drafts map, or the next rebuild would carry it as if the row existed.
        drafts.remove(category);
        rebuildWidgets();
    }

    /**
     * Opens the colour picker over this window.
     *
     * <p>The picker hands its result back here rather than knowing about the store, because a swatch
     * belongs to one category and only this screen knows which. The colour is written on the
     * picker's Done and nowhere else, which is what makes Cancel a real answer: a colour is not a
     * draft on this screen, it is applied or it is not.
     */
    private void openPicker(RoadCategory category) {
        Screens.set(new RoadCategoryColorScreen(this, RoadCategoryStore.color(category),
                colour -> RoadCategoryStore.setColor(category, colour)));
    }

    /**
     * Scrolls the list by whole bands until a category's band is inside the window.
     *
     * <p>Only ever scrolls downwards and only as far as it must: the largest first-band index that
     * still shows the whole row is chosen, so the row lands at the bottom of the window with its
     * group above it rather than alone at the top.
     */
    private void reveal(RoadCategory category) {
        int index = indexOf(category);
        if (index < 0 || items.isEmpty()) {
            return;
        }
        Item target = items.get(index);
        int top = items.get(scroll).offset;
        if (target.offset + target.height - top <= listH) {
            // Already on screen: the new band is where the player is looking.
            layoutFields();
            return;
        }
        int first = scroll;
        for (int candidate = index; candidate >= 0; candidate--) {
            if (target.offset + target.height - items.get(candidate).offset <= listH) {
                first = candidate;
                break;
            }
        }
        if (first != scroll) {
            scroll = clampScroll(first);
            buildRowButtons();
        }
        layoutFields();
    }

    /** The band that shows a category, or -1 when the set no longer has it. */
    private int indexOf(RoadCategory category) {
        for (int i = 0; i < items.size(); i++) {
            if (category.equals(items.get(i).category)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Nothing, deliberately.
     *
     * <p>Overriding it is not optional: the inherited body draws the vanilla blurred menu background,
     * which would sit under a window that paints its own wash and sheet. Painting nothing here is
     * also what lets {@link #render} paint the sheet and the rows before the game paints the name
     * boxes over them -- see the class note.
     */
    @Override
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The frame's motion first, before anything is drawn: every colour below is read at the
        // opacity it answers with, and UiMotion.of is also what pumps a deferred screen swap, so it
        // is asked exactly once per frame rather than once per band.
        UiMotion.ScreenState motion = UiMotion.of(this);
        open = motion.opacity();
        // The window is displaced as one piece -- wash, sheet, bands, boxes and footer -- so it rises
        // into place instead of being cut on. The pose is rebuilt by the game every frame, so the
        // pop at the end of this method is the whole of the bookkeeping.
        UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));

        // The wash reaches past the window's corners because it is drawn inside the displaced frame:
        // a scrim cut to the window would leave an undimmed strip along the edge the rise moved away
        // from. The sheet below moves with the frame and needs no margin.
        graphics.fill(-UiMotion.SCRIM_MARGIN, -UiMotion.SCRIM_MARGIN,
                this.width + UiMotion.SCRIM_MARGIN, this.height + UiMotion.SCRIM_MARGIN,
                UiMotion.fade(UiTheme.dim(), open));
        // Rounded like the settings window's sheet rather than square: this is a window over the
        // game, and it replaces that window, so it should look like the same kind of surface.
        HudDraw.fillRounded(graphics, panelX, panelY, panelX + panelW, panelY + panelH, 5,
                UiMotion.fade(UiTheme.panel(), open));
        HudDraw.outlineRounded(graphics, panelX, panelY, panelX + panelW, panelY + panelH, 5,
                UiMotion.fade(UiTheme.panelEdge(), open));

        drawFrame(graphics);
        drawList(graphics, mouseX, mouseY);
        // The boxes are placed and hidden before the widget pass, and drawn by it: they are the one
        // part of this screen the game owns, which is why they are not painted above.
        layoutFields();
        super.render(graphics, mouseX, mouseY, partialTick);
        // The row buttons are drawn after the widget pass rather than before it: they sit beside the
        // boxes, not under them, and a button that was a real widget would be the one control on the
        // screen with the game's own look.
        for (PanelButton button : rowButtons) {
            button.render(graphics, font, mouseX, mouseY);
        }
        for (PanelButton button : footerButtons) {
            button.render(graphics, font, mouseX, mouseY);
        }
        UiMotion.popFrame(graphics);
    }

    /** The title, the hint and the hairlines that separate the window's three bands. */
    private void drawFrame(GuiGraphics graphics) {
        graphics.drawString(font, this.title, panelX + PAD, panelY + PAD,
                UiMotion.fade(UiTheme.title(), open), false);
        graphics.fill(listX, listY - 4, listX + listW, listY - 3,
                UiMotion.fade(UiTheme.panelEdge(), open));
        if (!hintVisible) {
            return;
        }
        graphics.fill(listX, hintY - 4, listX + listW, hintY - 3,
                UiMotion.fade(UiTheme.panelEdge(), open));
        // The hint line doubles as the confirm's explanation: an armed delete is a state the player
        // put the screen in, so it has to say which row it is about and what the second press will do
        // -- a colour change of the button alone would be a mode with no words.
        Component hint = armedDelete == null
                ? Component.translatable("screen.howtogo.categories.hint")
                : Component.translatable("screen.howtogo.categories.delete_hint",
                        RoadCategoryStore.displayName(armedDelete));
        int colour = armedDelete == null ? UiTheme.textFaint() : UiTheme.warn();
        graphics.drawString(font, PlaceText.ellipsis(font, hint.getString(), listW - 4),
                listX + 2, hintY, UiMotion.fade(colour, open), false);
    }

    /**
     * The list: group headers, the built-in row, the player's categories, and the add button of each
     * group, inside the viewport.
     *
     * <p>Clipped to the list's rectangle even though every band drawn is whole: the clip is the
     * viewport's own edge, and it is the one place that keeps a band, a swatch or a header word from
     * reaching the title, the hint or the scrollbar if a later row is taller than this one. It goes
     * through {@link UiMotion#clip} so the scissor follows the displaced frame.
     */
    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        if (items.isEmpty()) {
            return;
        }
        int top = items.get(scroll).offset;
        UiMotion.clip(graphics, listX, listY, listX + listW, listY + listH);
        for (int i = scroll; i < items.size(); i++) {
            Item item = items.get(i);
            int bandY = listY + item.offset - top;
            if (!bandVisible(bandY, item)) {
                // The window ends here: the next band is not drawn at all rather than drawn cut in
                // half, because the rows under it have widgets that could not be cut with them.
                break;
            }
            float arrived = UiMotion.stagger(i, open);
            switch (item.kind) {
                case HEADER -> graphics.drawString(font, item.label, listX + 2,
                        bandY + (HEADER_HEIGHT - 8) / 2,
                        UiMotion.fade(UiTheme.header(), arrived), false);
                case BUILT_IN -> {
                    drawSwatch(graphics, item, bandY, 0.0F, arrived);
                    // The built-in category's name is drawn instead of a box: it is translated by
                    // the store on every read and cannot be renamed, so there is nothing to type
                    // into -- and the muted rank says "this is fixed" before the player tries.
                    graphics.drawString(font, PlaceText.ellipsis(font,
                                    RoadCategoryStore.displayName(item.category).getString(),
                                    listX + listW - 8 - fieldX),
                            fieldX, bandY + (ROW_HEIGHT - 8) / 2,
                            UiMotion.fade(UiTheme.textMuted(), arrived), false);
                }
                case CATEGORY -> drawSwatch(graphics, item, bandY,
                        swatchHovers.get(i).to(swatchHovered(item, bandY, mouseX, mouseY)
                                ? 1.0F : 0.0F).eased(), arrived);
                // The add band's only content is a button, drawn after the widget pass; nothing is
                // painted here for it -- the band exists for its height.
                default -> { }
            }
        }
        graphics.disableScissor();
        drawScrollbar(graphics);
    }

    /**
     * One category's colour marker: the one thing on the row a press changes.
     *
     * <p>Filled with the store's colour and ringed with the theme's icon grey, because that is what
     * every list row in this mod already uses for "this is the thing's colour" -- and a category may
     * be given a near-white colour, which without the ring would vanish into a white sheet. The ring
     * moves towards the accent under the cursor, because on a player-made row the square is a button
     * that opens the picker rather than a readout.
     */
    private void drawSwatch(GuiGraphics graphics, Item item, int bandY, float lit, float arrived) {
        int x = listX + SWATCH_X;
        int y = bandY + (ROW_HEIGHT - SWATCH) / 2;
        int colour = UiMotion.fade(RoadCategoryStore.color(item.category), arrived);
        int edge = UiMotion.fade(UiMotion.mix(UiTheme.icon(), UiTheme.accent(), lit), arrived);
        HudDraw.fillRounded(graphics, x, y, x + SWATCH, y + SWATCH, 2, colour);
        HudDraw.outlineRounded(graphics, x, y, x + SWATCH, y + SWATCH, 2, edge);
    }

    /**
     * The scrollbar: a track and a thumb, drawn only when the list is taller than the window.
     *
     * <p>Wheel-only, like every other list in this mod; a 2 px bar is an indicator here, not a second
     * input surface, which is why no drag state is kept for it.
     */
    private void drawScrollbar(GuiGraphics graphics) {
        if (contentHeight <= listH) {
            return;
        }
        int trackX = listX + listW - 2;
        int thumbHeight = Math.max(16, listH * listH / contentHeight);
        int maximum = maxScroll();
        int thumbY = listY + (int) ((listH - thumbHeight)
                * (maximum <= 0 ? 0.0 : (double) scroll / maximum));
        graphics.fill(trackX, listY, trackX + 2, listY + listH,
                UiMotion.fade(UiTheme.scrollTrack(), open));
        graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight,
                UiMotion.fade(UiTheme.scrollThumb(), open));
    }

    // ----------------------------------------------------------------- geometry

    /**
     * The largest first-band index whose page still fits in the window.
     *
     * <p>Derived from the bands rather than from the content height so the last page is the last
     * bands rather than a fixed number of pixels above the end: the list's last bands are a row with
     * a name box and a group's add button, and a scroll clamp that allowed a page past them would
     * leave one of those out of reach at the bottom of the content.
     */
    private int maxScroll() {
        for (int i = 0; i < items.size(); i++) {
            if (contentHeight - items.get(i).offset <= listH) {
                return i;
            }
        }
        return Math.max(0, items.size() - 1);
    }

    private int clampScroll(int value) {
        return Math.max(0, Math.min(maxScroll(), value));
    }

    /** Whether a band drawn at {@code bandY} is wholly inside the list's viewport. */
    private boolean bandVisible(int bandY, Item item) {
        return bandY >= listY && bandY + item.height <= listY + listH;
    }

    private boolean insideList(double mouseX, double mouseY) {
        return mouseX >= listX && mouseX < listX + listW
                && mouseY >= listY && mouseY < listY + listH;
    }

    private boolean swatchHovered(Item item, int bandY, double mouseX, double mouseY) {
        int x = listX + SWATCH_X;
        int y = bandY + (ROW_HEIGHT - SWATCH) / 2;
        return mouseX >= x && mouseX < x + SWATCH && mouseY >= y && mouseY < y + SWATCH;
    }

    /**
     * The player-made category whose swatch is under the cursor, or null.
     *
     * <p>Only player-made rows answer: the built-in category's square is a statement of what an
     * unclassified road is, not a control, so a press on it does nothing.
     */
    private RoadCategory categorySwatchAt(double mouseX, double mouseY) {
        if (!insideList(mouseX, mouseY) || items.isEmpty()) {
            return null;
        }
        int top = items.get(scroll).offset;
        for (int i = scroll; i < items.size(); i++) {
            Item item = items.get(i);
            int bandY = listY + item.offset - top;
            if (!bandVisible(bandY, item)) {
                break;
            }
            if (item.kind == Kind.CATEGORY && swatchHovered(item, bandY, mouseX, mouseY)) {
                return item.category;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------- bands

    private enum Kind {
        /** A class header: a word, no control. */
        HEADER,
        /** The built-in "not classified" row: a fixed swatch and a translated name. */
        BUILT_IN,
        /** A player-made category: a picker swatch, a name box and the delete button. */
        CATEGORY,
        /** The band that carries a group's add button. */
        ADD
    }

    /** One band of the list: a class header, a category, or the add button of a group. */
    private static final class Item {
        private final Kind kind;
        private final RoadClass roadClass;
        /** The category this band edits; null on header and add bands. */
        private final RoadCategory category;
        /** The header's translated class name; null on every other band. */
        private final Component label;
        private final int height;
        /** Top edge in content coordinates, filled by {@link #rebuildItems()}. */
        private int offset;

        private Item(Kind kind, RoadClass roadClass, RoadCategory category, Component label,
                     int height) {
            this.kind = kind;
            this.roadClass = roadClass;
            this.category = category;
            this.label = label;
            this.height = height;
        }

        private static Item header(RoadClass roadClass) {
            return new Item(Kind.HEADER, roadClass, null, Component.translatable(
                    "screen.howtogo.road_class." + roadClass.name().toLowerCase(Locale.ROOT)),
                    HEADER_HEIGHT);
        }

        private static Item builtIn(RoadClass roadClass, RoadCategory category) {
            return new Item(Kind.BUILT_IN, roadClass, category, null, ROW_HEIGHT);
        }

        private static Item category(RoadClass roadClass, RoadCategory category) {
            return new Item(Kind.CATEGORY, roadClass, category, null, ROW_HEIGHT);
        }

        private static Item addButton(RoadClass roadClass) {
            return new Item(Kind.ADD, roadClass, null, null, ADD_HEIGHT);
        }
    }
}
