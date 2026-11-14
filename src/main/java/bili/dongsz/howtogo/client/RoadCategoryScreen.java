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
 * The editor behind the road-label setting: every category's name and colour in one window.
 * A road's RoadClass decides how it is drawn; its RoadCategory is the classification one level below -- the plate a road wears on the map (national vs provincial highway, trunk street vs alley, trunk line vs branch). Categories are the player's to define; each class has exactly one built-in not-classified category, drawn but not renameable, deletable or pickable.
 * Names are drafts read on the way out: Done and Escape keep them, Cancel drops them, and a blank box means the store's default name. Colours and the set are immediate -- the picker's Done writes the colour, and add and delete hit the store at once.
 * Deleting is irreversible, so it takes two presses and the hint explains the armed row.
 * The list scrolls by whole bands because the name boxes are real widgets that cannot be clipped to a half-visible row.
 * The window is painted from render, not renderBackground, so the sheet precedes the widget pass on both 1.20.1 and later; renderBackground stays overridden and empty to suppress the vanilla blur and wash.
 */
final class RoadCategoryScreen extends Screen {

    private static final int PAD = 10;
    private static final int GAP = 6;
    private static final int TITLE_HEIGHT = 16;
    private static final int LINE_HEIGHT = 10;
    private static final int BUTTON_HEIGHT = 18;
    private static final int HEADER_HEIGHT = 18;
    private static final int ROW_HEIGHT = 24;
    private static final int ADD_HEIGHT = 20;
    private static final int FIELD_HEIGHT = 16;
    private static final int ROW_BUTTON_HEIGHT = 16;
    private static final int SWATCH = 14;
    private static final int SWATCH_X = 8;
    private static final int FIELD_GAP = 8;
    /**
     * Wide enough to carry the confirming word instead of the cross without clipping it: the armed label is what tells the player the second press acts.
     */
    private static final int DELETE_WIDTH = 34;
    private static final int ADD_WIDTH = 150;
    private static final int SCROLL_STEP = 1;
    /**
     * A name is drawn on a sign card, so it is a label rather than a sentence; the same budget as the road-name field.
     */
    private static final int MAX_NAME_LENGTH = 48;
    /**
     * The store's own name for a category nobody has named. Repeated here because it is the field's hint and what a blank box is compared against: the store falls back to it whenever a name is blank.
     */
    private static final String NAME_PLACEHOLDER_KEY = "howtogo.road_category.new";

    private final Screen parent;

    /**
     * Every band in drawing order, rebuilt when the set of categories changes. Adding or deleting changes it, and the offsets it carries are what the scroll, the drawing, the boxes and the row buttons read.
     */
    private final List<Item> items = new ArrayList<>();

    /** The name box of each player-made category, live from init() until the screen closes. */
    private final Map<RoadCategory, EditBox> fields = new LinkedHashMap<>();

    /**
     * Names typed so far, lifted out of the boxes before they are rebuilt. init() runs again on a resize and on the way back from the colour picker, and a typed name exists only in the old box.
     */
    private final Map<RoadCategory, String> drafts = new LinkedHashMap<>();

    /**
     * The delete cross of each row and the add button of each group, rebuilt whenever the rows move -- a PanelButton is a fixed rectangle, so it travels with its band.
     */
    private List<PanelButton> rowButtons = List.of();

    private List<PanelButton> footerButtons = List.of();

    /** One hover slide per band that owns a swatch, so the ring lights instead of switching. */
    private final UiMotion.Slides swatchHovers = new UiMotion.Slides();

    /**
     * The category whose delete button is armed, or null. Screen state rather than button state because a scroll notch rebuilds every row button.
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
    /** Whether the window is tall enough for the hint line under the list; see init(). */
    private boolean hintVisible;

    /**
     * The first band drawn: an index into items, not a pixel offset. No smoothing for the same reason -- every drawn band must be whole.
     */
    private int scroll;

    /**
     * Whether this visit ended with the footer's Cancel. Escape closes through onClose, which saves; without this flag a Cancel pressed just before Escape would be undone.
     */
    private boolean cancelled;

    /** The opacity the whole window is drawn at this frame; see render. */
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
     * Lays the list out: a header per class, its categories, then its add button, in the declaration order players expect to read a network in. The store answers with the built-in category first, then player-made ones in creation order.
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
     * Builds one name box per player-made category; the seed is the draft if any, else the store's name, so the box shows what the sign will carry. The built-in category has no box because it cannot be renamed.
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
     * Reads every live box into the drafts and forgets the boxes, so an uncommitted name survives a rebuild. A box whose category was deleted is skipped rather than carried.
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
     * Puts each name box where its band is drawn this frame and hides the ones out of the window -- widgets cannot be clipped to the list, which is why the list scrolls by whole bands.
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
     * Builds the delete cross of every visible row and the add button of every group. Rebuilt rather than moved because a PanelButton is a fixed rectangle; the armed category gets the confirming label.
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
        // Done keeps the names, so it takes the accent; Cancel drops them and is an ordinary button.
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
            // Row buttons next; a snapshot is taken because a press on one rebuilds the list it lives in.
            List<PanelButton> rowSnapshot = rowButtons;
            for (PanelButton candidate : rowSnapshot) {
                if (candidate.click(mouseX, mouseY)) {
                    return true;
                }
            }
            RoadCategory swatch = categorySwatchAt(mouseX, mouseY);
            if (swatch != null) {
                // A press on a swatch opens the picker; consume it so it is not also a press on the box beside it.
                armedDelete = null;
                openPicker(swatch);
                return true;
            }
            // Any other press disarms the delete confirm; the press is still offered to the widgets below.
            if (armedDelete != null) {
                armedDelete = null;
                buildRowButtons();
            }
        } else if (armedDelete != null) {
            // Right and middle presses are still "any other press" and take the confirm back.
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
        // Enter is Done; Escape still closes and saves through the game's own handler.
        if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
            done();
            return true;
        }
        // Tab walks the visible boxes in drawn order, not the game's cycle into scrolled-out ones.
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
     * Moves the focus to the next visible box, wrapping. List order, not widget order, so Tab reads down the window.
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
    // 1.20.2 split the wheel delta into horizontal and vertical; only the signature differs.
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
     * Cancel leaves without committing the names; colours and the set cannot be undone from here, because each was applied when it was pressed.
     */
    private void cancel() {
        cancelled = true;
        Screens.set(parent);
    }

    @Override
    public void onClose() {
        // Escape is a close, not Cancel: it keeps the typed names, and cancelled stops a close racing Cancel.
        if (!cancelled) {
            saveNames();
        }
        Screens.set(parent);
    }

    /**
     * Writes each box's text to the store; a blank line restores the default name. Writing every box unconditionally would freeze names in whatever language the page was opened in, so a box holding the placeholder is written as blank.
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
     * Makes a category for a class, then re-lays the rows so its band appears. The store has already written the definition; re-init is the whole of it, and liftDrafts keeps the typed names.
     */
    private void addCategory(RoadClass roadClass) {
        RoadCategory created = RoadCategoryStore.add(roadClass);
        armedDelete = null;
        rebuildWidgets();
        // A new band can be below the window; scroll to it rather than leaving the button seemingly inert.
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
     * Deletes a category and reclassifies the roads that wear it as not classified. The store deletes definitions, not roads; a dangling id would come back to life if it were ever reused. Marking the network dirty lets the cleanup reach the save with the next autosave.
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
     * Opens the colour picker over this window; it hands its result back here because only this screen knows the category. A colour is written on the picker's Done and nowhere else, so Cancel is a real answer.
     */
    private void openPicker(RoadCategory category) {
        Screens.set(new RoadCategoryColorScreen(this, RoadCategoryStore.color(category),
                colour -> RoadCategoryStore.setColor(category, colour)));
    }

    /**
     * Scrolls by whole bands until a category's band is inside the window, only downwards and only as far as it must, so the row lands at the bottom with its group above it.
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
     * Nothing, deliberately. The inherited body draws the vanilla blurred background under a window that paints its own; painting nothing also lets render paint the sheet before the widget pass.
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
        // Frame motion first: colours below read its opacity, and UiMotion.of pumps a deferred swap once.
        UiMotion.ScreenState motion = UiMotion.of(this);
        open = motion.opacity();
        // The window is displaced as one piece -- wash, sheet, bands, boxes and footer -- so it rises
        // into place instead of being cut on. The pose is rebuilt by the game every frame, so the
        // pop at the end of this method is the whole of the bookkeeping.
        UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));

        // Wash overruns the corners because it is drawn inside the displaced frame.
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
        // The hint doubles as the confirm's explanation: which row is armed and what the second press does.
        Component hint = armedDelete == null
                ? Component.translatable("screen.howtogo.categories.hint")
                : Component.translatable("screen.howtogo.categories.delete_hint",
                        RoadCategoryStore.displayName(armedDelete));
        int colour = armedDelete == null ? UiTheme.textFaint() : UiTheme.warn();
        graphics.drawString(font, PlaceText.ellipsis(font, hint.getString(), listW - 4),
                listX + 2, hintY, UiMotion.fade(colour, open), false);
    }

    /**
     * The list: group headers, the built-in row, the player's categories and each group's add button, inside the viewport. Clipped even though every band is whole -- it keeps a later row from reaching the title or hint, and it goes through UiMotion.clip so the scissor follows the displaced frame.
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
                    // The built-in name is drawn instead of a box: it cannot be renamed, and the muted rank says so first.
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
     * One category's colour marker. Filled with the store's colour and ringed with the theme's icon grey, because a near-white colour would otherwise vanish into the sheet; the ring moves to the accent under the cursor, where the square is a button.
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
     * The scrollbar: a track and a thumb, drawn only when the list is taller than the window. Wheel-only, so the bar is an indicator and keeps no drag state.
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
     * The largest first-band index whose page still fits, derived from the bands so the last page is the last bands rather than a fixed pixel height.
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

    /** Whether a band drawn at bandY is wholly inside the list's viewport. */
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
     * The player-made category whose swatch is under the cursor, or null. The built-in square is a readout, not a control.
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
        /** Top edge in content coordinates, filled by rebuildItems(). */
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
