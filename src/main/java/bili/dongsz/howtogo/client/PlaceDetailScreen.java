package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.place.PlaceDetails;
import bili.dongsz.howtogo.place.PlaceSections;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.transit.TransitLine;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
// 1.21.9 bundled mouse coordinates and the button into a record, the way RoadNameScreen unpacks it.
//? if >=1.21.9 {
/*import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
*///?}
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The place-details dialog: a description, tags and a favourite flag, over whatever screen opened it.
 * Two ways in, one dialog: openForStore is the read-and-edit card whose Done writes PlaceDetailStore
 * directly; openForDraft hands the record back and writes nothing, because the place edit panel's
 * Done is the single commit and a dialog that saved by itself would make the panel's Cancel a lie.
 * The card is drawn over a re-render of the parent, like RoadNameScreen, because the map underneath
 * is the context for the place being described.
 * The description is a hand-drawn MultilineField: neither EditBox nor MultiLineEditBox exists on
 * every node of this tree. Tags stay a vanilla EditBox.
 * Limits are checked here before the store's normalisation, which would silently lose text;
 * refusing with OVER_LIMIT_KEY and staying open is the difference between "too long" and "gone".
 */
public final class PlaceDetailScreen extends Screen implements ParentBackdrop {

    /**
     * The dialog's own name: this is the editor, not the read-only card. Also the heading for a place
     * that is nameless until the player types a name.
     */
    public static final String TITLE_KEY = "screen.howtogo.place_details.edit_title";
    public static final String SOURCE_KEY = "screen.howtogo.place_details.source";
    public static final String DESCRIPTION_KEY = "screen.howtogo.place_details.description";
    public static final String DESCRIPTION_HINT_KEY = "screen.howtogo.place_details.description_hint";
    public static final String TAGS_KEY = "screen.howtogo.place_details.tags";
    public static final String TAGS_HINT_KEY = "screen.howtogo.place_details.tags_hint";
    /**
     * "Tags %s/%s" for the counter beside the tags caption; the description needs none because its box
     * refuses a 513th character.
     */
    public static final String TAGS_COUNT_KEY = "screen.howtogo.place_details.tags_count";
    public static final String FAVORITE_KEY = "screen.howtogo.place_details.favorite";
    public static final String FAVORITED_KEY = "screen.howtogo.place_details.favorited";
    /** Marks draft mode: what is written here is not stored until the place panel's Done. */
    public static final String DRAFT_KEY = "screen.howtogo.place_details.draft";
    /** The refusal when the typed text is longer than a limit allows. */
    public static final String OVER_LIMIT_KEY = "screen.howtogo.place_details.over_limit";
    /** The button in the place edit panel that opens this dialog as a draft. */
    public static final String MORE_KEY = "screen.howtogo.place_details.more";

    /** The caption before a station's read-only line chips. */
    public static final String LINES_KEY = "screen.howtogo.place_details.lines";

    /** The type box hint; separate from the tag hint because it is one value, not a list. */
    public static final String CATEGORY_HINT_KEY = "screen.howtogo.place_details.category_hint";

    /** The hint in the resource-type box: a gathered resource, not a shop. */
    public static final String RESOURCE_HINT_KEY =
            "screen.howtogo.place_details.resource_hint";

    /**
     * Tags are cut on either comma, either Oriental semicolon, or whitespace; generous because the field
     * is typed with Chinese input methods as often as on a keyboard.
     */
    private static final String TAG_SEPARATORS = "[,，;；、\\s]+";

    private static final int PAD = 8;
    private static final int GAP = 6;
    private static final int CARD_MAX_WIDTH = 320;
    private static final int CARD_MIN_WIDTH = 200;
    private static final int TITLE_HEIGHT = 11;
    private static final int SOURCE_HEIGHT = 10;
    private static final int CAPTION_HEIGHT = 9;
    private static final int DESCRIPTION_MIN_HEIGHT = 40;
    /** The absolute floor, for a window shorter than the card's fixed rows. One line, clipped. */
    private static final int DESCRIPTION_CRAMPED_HEIGHT = 20;
    private static final int DESCRIPTION_MAX_HEIGHT = 90;
    private static final int TAG_FIELD_HEIGHT = 16;
    private static final int TOGGLE_HEIGHT = 16;
    private static final int ADDRESS_HEIGHT = 10;
    /**
     * The error line: two lines' worth, reserved whether or not there is a refusal. The limits message
     * names three numbers and does not fit one line at the card's narrowest width.
     */
    private static final int ERROR_LINES = 2;
    private static final int ERROR_LINE_HEIGHT = 10;
    private static final int ERROR_HEIGHT = ERROR_LINES * ERROR_LINE_HEIGHT;
    private static final int BUTTON_HEIGHT = 16;
    private static final int CORNER_RADIUS = 5;
    private static final int TOGGLE_MARK = 6;

    /*
     * The card's rows in reading order. Each starts UiMotion.STAGGER ms after the one above, which is
     * what makes the dialog look written rather than switched on.
     */
    private static final int ROW_TITLE = 0;
    private static final int ROW_SOURCE = 1;
    private static final int ROW_ADDRESS = 2;
    private static final int ROW_LINES = 3;
    private static final int ROW_TYPE = 4;
    private static final int ROW_DESCRIPTION = 5;
    private static final int ROW_TAGS = 6;
    private static final int ROW_FAVORITE = 7;
    private static final int ROW_ERROR = 8;

    private final Screen parent;
    private final Destination place;
    private final Consumer<PlaceDetails> onAccept;
    private final boolean draft;
    private final MultilineField description;

    private boolean favorite;

    private boolean descriptionFocused = true;

    private EditBox tagsField;
    /** The tag text, carried through init() so a resize does not lose it either. */
    private String tagsText;

    private EditBox categoryField;
    /** The type as it stood when the dialog opened, carried across a resize. */
    private String categoryText;

    private Component error;

    /** Done and cancel, built in init() and drawn and hit-tested by hand. */
    private List<PanelButton> buttons = List.of();

    private float open = 1.0F;

    /**
     * The favourite switch's hover, the one control on this card that answers the cursor. A field
     * because the switch is redrawn every frame; a slide made per frame would never travel.
     */
    private final UiMotion.Slides hovers = new UiMotion.Slides();

    private int cardX;
    private int cardY;
    private int cardW;
    private int cardH;
    private int contentX;
    private int contentW;
    private int titleY;
    private int sourceY;
    private int addressY;
    private int linesY;
    private int categoryCaptionY;
    private int categoryY;
    private int descriptionCaptionY;
    private int descriptionY;
    private int descriptionH;
    private int tagsCaptionY;
    private int tagsY;
    private int favoriteY;
    private int errorY;
    private int buttonY;

    private PlaceDetailScreen(Screen parent, Destination place, PlaceDetails initial,
                              Consumer<PlaceDetails> onAccept, boolean draft) {
        super(Component.translatable(TITLE_KEY));
        this.parent = parent;
        this.place = place;
        this.onAccept = onAccept;
        this.draft = draft;
        PlaceDetails start = initial == null ? PlaceDetails.EMPTY : initial;
        this.description = new MultilineField(PlaceDetails.MAX_DESCRIPTION,
                Component.translatable(DESCRIPTION_HINT_KEY), start.description());
        this.favorite = start.favorite();
        this.tagsText = joinTags(start.tags());
        this.categoryText = start.category();
    }

    public static void openForStore(Screen parent, Destination place) {
        if (place == null) {
            return;
        }
        PlaceDetails initial = PlaceDetailStore.detailsOf(place);
        Screens.set(new PlaceDetailScreen(parent, place, initial,
                details -> PlaceDetailStore.put(place, details), false));
    }

    /**
     * Opens the dialog as a draft: Done hands the record to onAccept and writes nothing, because the
     * place edit panel's own Done is the single commit of a place edit.
     */
    public static void openForDraft(Screen parent, Destination place, PlaceDetails initial,
                                    Consumer<PlaceDetails> onAccept) {
        if (place == null || onAccept == null) {
            return;
        }
        Screens.set(new PlaceDetailScreen(parent, place, initial, onAccept, true));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        cardW = Math.min(CARD_MAX_WIDTH, Math.max(CARD_MIN_WIDTH, width - 16));
        contentW = cardW - PAD * 2;

        boolean showsLines = showsLines();
        boolean showsType = showsType();

        int fixed = PAD + TITLE_HEIGHT + 2
                + SOURCE_HEIGHT + GAP
                + ADDRESS_HEIGHT + GAP
                + (showsLines ? ADDRESS_HEIGHT + GAP : 0)
                + (showsType ? CAPTION_HEIGHT + 2 + TAG_FIELD_HEIGHT + GAP : 0)
                + CAPTION_HEIGHT + 2
                + GAP
                + CAPTION_HEIGHT + 2
                + TAG_FIELD_HEIGHT + GAP
                + TOGGLE_HEIGHT + GAP
                + ERROR_HEIGHT + GAP
                + BUTTON_HEIGHT + PAD;
        int spare = height - 16 - fixed;
        descriptionH = Mth.clamp(spare, DESCRIPTION_MIN_HEIGHT, DESCRIPTION_MAX_HEIGHT);
        if (descriptionH > spare) {
            descriptionH = Math.max(DESCRIPTION_CRAMPED_HEIGHT, spare);
        }
        cardH = fixed + descriptionH;
        cardX = Math.max(0, (width - cardW) / 2);
        cardY = Math.max(0, (height - cardH) / 2);
        contentX = cardX + PAD;

        int cursorY = cardY + PAD;
        titleY = cursorY;
        cursorY += TITLE_HEIGHT + 2;
        sourceY = cursorY;
        cursorY += SOURCE_HEIGHT + GAP;
        addressY = cursorY;
        cursorY += ADDRESS_HEIGHT + GAP;
        if (showsLines) {
            linesY = cursorY;
            cursorY += ADDRESS_HEIGHT + GAP;
        }
        if (showsType) {
            categoryCaptionY = cursorY;
            cursorY += CAPTION_HEIGHT + 2;
            categoryY = cursorY;
            cursorY += TAG_FIELD_HEIGHT + GAP;
        }
        descriptionCaptionY = cursorY;
        cursorY += CAPTION_HEIGHT + 2;
        descriptionY = cursorY;
        cursorY += descriptionH + GAP;
        tagsCaptionY = cursorY;
        cursorY += CAPTION_HEIGHT + 2;
        tagsY = cursorY;
        cursorY += TAG_FIELD_HEIGHT + GAP;
        favoriteY = cursorY;
        cursorY += TOGGLE_HEIGHT + GAP;
        errorY = cursorY;
        cursorY += ERROR_HEIGHT + GAP;
        buttonY = cursorY;

        description.setBounds(contentX, descriptionY, contentW, descriptionH);

        String seed = tagsField != null ? tagsField.getValue() : tagsText;
        tagsField = new EditBox(this.font, contentX, tagsY, contentW, TAG_FIELD_HEIGHT,
                Component.translatable(TAGS_KEY));
        tagsField.setHint(Component.translatable(TAGS_HINT_KEY));
        // A tag is at most eight characters and there are at most sixteen; the box takes the room those
        // need with their separators.
        tagsField.setMaxLength(PlaceDetails.MAX_TAGS * (PlaceDetails.MAX_TAG_LENGTH + 1));
        tagsField.setValue(seed == null ? "" : seed);
        addRenderableWidget(tagsField);

        if (showsType) {
            String categorySeed = categoryField != null ? categoryField.getValue() : categoryText;
            // The box refuses a ninth character rather than letting the player type one and then refusing Done:
            // the field's own limit and the rule are the same number.
            categoryField = new EditBox(this.font, contentX, categoryY, contentW, TAG_FIELD_HEIGHT,
                    Component.translatable(PlaceSections.labelKey(place.kind())));
            // A resource point's example is a resource, not a shop: "e.g. convenience store" under "Resource
            // type" would tell the player to write the wrong sort of word.
            categoryField.setHint(Component.translatable(
                    place.kind() == PlaceKind.RESOURCE ? RESOURCE_HINT_KEY : CATEGORY_HINT_KEY));
            categoryField.setMaxLength(PlaceDetails.MAX_TAG_LENGTH);
            categoryField.setValue(categorySeed == null ? "" : categorySeed);
            addRenderableWidget(categoryField);
        } else {
            // A kind that has no type shows no box; the value is kept in categoryText so changing a place's
            // kind does not erase what was typed.
            categoryField = null;
        }

        if (descriptionFocused) {
            // The description is what the player came to write; the tags box is a Tab away.
            setFocused(null);
        } else {
            setFocused(tagsField);
        }

        int half = (contentW - GAP) / 2;
        // Done is the action the dialog exists for, so it takes the accent; cancel is ordinary.
        buttons = List.of(
                new PanelButton(contentX, buttonY, half, BUTTON_HEIGHT,
                        Component.translatable("gui.done"), PanelButton.Kind.PRIMARY, this::accept),
                new PanelButton(contentX + half + GAP, buttonY, contentW - half - GAP, BUTTON_HEIGHT,
                        Component.translatable("gui.cancel"), PanelButton.Kind.NEUTRAL, this::onClose));
    }

    /** Whether this kind of place carries a type / resource type the player may write. */
    private boolean showsType() {
        return place.kind() == PlaceKind.SHOP || place.kind() == PlaceKind.RESOURCE;
    }

    /** Whether this kind of place shows the lines calling at it. */
    private boolean showsLines() {
        return place.kind() == PlaceKind.STATION;
    }


    /**
     * Checks the raw text against the limits, then normalises and hands the record over. The check must
     * be on what was typed, before PlaceDetails.normalize() trims and truncates; afterwards it always
     * passes.
     */
    private void accept() {
        // The last refusal was about the text as it stood then; every attempt starts clean.
        error = null;
        List<String> typedTags = parseTags(tagsField.getValue());
        String typedCategory = categoryField != null ? categoryField.getValue() : categoryText;
        boolean overLimit = PlaceDetails.lengthInCodePoints(description.value())
                > PlaceDetails.MAX_DESCRIPTION
                || typedTags.size() > PlaceDetails.MAX_TAGS
                || PlaceDetails.lengthInCodePoints(typedCategory) > PlaceDetails.MAX_TAG_LENGTH;
        if (!overLimit) {
            for (String tag : typedTags) {
                if (PlaceDetails.lengthInCodePoints(tag) > PlaceDetails.MAX_TAG_LENGTH) {
                    overLimit = true;
                    break;
                }
            }
        }
        if (overLimit) {
            // One message for every field: the type shares a tag's length limit, and a second sentence would be
            // the same numbers with a different noun.
            error = Component.translatable(OVER_LIMIT_KEY, PlaceDetails.MAX_DESCRIPTION,
                    PlaceDetails.MAX_TAGS, PlaceDetails.MAX_TAG_LENGTH);
            return;
        }
        onAccept.accept(PlaceDetails.of(description.value(), typedTags, favorite)
                .withCategory(typedCategory).normalize());
        onClose();
    }

    /**
     * The typed tag text cut into tags, blanks dropped and nothing yet trimmed or de-duplicated. The
     * store does the trimming, and the counter has to describe what the player typed.
     */
    private static List<String> parseTags(String raw) {
        List<String> tags = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return tags;
        }
        for (String piece : raw.split(TAG_SEPARATORS)) {
            if (!piece.isBlank()) {
                tags.add(piece);
            }
        }
        return tags;
    }

    /** The stored tags as one editable line, joined with the comma the hint tells the player to type. */
    private static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        return String.join(",", tags);
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
        if (button == MouseButton.LEFT) {
            // The drawn buttons first: they are not widgets, so this is their only chance at the click, and a
            // press on one must not also move the caret under it.
            for (PanelButton candidate : buttons) {
                if (candidate.click(mouseX, mouseY)) {
                    return true;
                }
            }
            if (insideFavorite(mouseX, mouseY)) {
                favorite = !favorite;
                // The old refusal was not about this switch, so a successful change clears it.
                error = null;
                return true;
            }
            if (description.contains(mouseX, mouseY)) {
                // The custom field becomes the focused thing, which on a plain Screen means no widget is focused:
                // the flag above is what charTyped reads.
                descriptionFocused = true;
                setFocused(null);
                description.mouseClicked(this.font, mouseX, mouseY);
                return true;
            }
            // Anywhere else leaves the description; a click on the tags box is handled by the widget itself.
            descriptionFocused = false;
        }
        //? if >=1.21.9 {
            /*return super.mouseClicked(new MouseButtonEvent(mouseX, mouseY,
                    new MouseButtonInfo(button, 0)), false);
            *///?} else {
            return super.mouseClicked(mouseX, mouseY, button);
            //?}
    }

    @Override
    // 1.21.9 made key input a record and moved key codes onto InputConstants. The record is unpacked on
    // entry so the body below reads the same either way, as RoadNameScreen does.
    //? if >=1.21.9 {
    /*public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key();
    *///?} else {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    //?}
        // Tab is the only way the two fields trade focus: a plain Screen has no tab order, and without this
        // the description could never be reached from the widgets.
        if (keyCode == InputConstants.KEY_TAB) {
            // Three fields at most, one cycle: description (not a widget, so it needs the flag), tag box, and
            // the type box for a shop or resource point.
            if (descriptionFocused) {
                descriptionFocused = false;
                setFocused(tagsField);
            } else if (categoryField != null && getFocused() != categoryField) {
                setFocused(categoryField);
            } else {
                descriptionFocused = true;
                setFocused(null);
            }
            return true;
        }
        if (descriptionFocused && description.keyPressed(this.font, keyCode)) {
            return true;
        }
        //? if >=1.21.9 {
        /*return super.keyPressed(event);
        *///?} else {
        return super.keyPressed(keyCode, scanCode, modifiers);
        //?}
    }

    @Override
    // 1.21.9 bundled committed characters into CharacterEvent, the same input-record change that renamed
    // the key and mouse events. The field takes an int either way.
    //? if >=1.21.9 {
    /*public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (descriptionFocused) {
            return description.charTyped(event.codepoint());
        }
        return super.charTyped(event);
    }
    *///?} else {
    public boolean charTyped(char codePoint, int modifiers) {
        if (descriptionFocused) {
            return description.charTyped(codePoint);
        }
        return super.charTyped(codePoint, modifiers);
    }
    //?}

    @Override
    //? if >=1.20.2 {
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    //?} else {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
    *///?}
        if (description.contains(mouseX, mouseY)) {
            description.scroll(this.font, scrollY);
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
        Screens.set(parent);
    }

    @Override
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}
    }



    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The frame's motion first, before anything is drawn: the dimming, sheet and rows all read it, and
        // UiMotion.of also pumps a deferred screen swap, so ask once per frame.
        UiMotion.ScreenState motion = UiMotion.of(this);
        open = motion.opacity();
        if (parent != null) {
            try {
                parent.render(graphics, mouseX, mouseY, partialTick);
            } catch (Throwable t) {
                // The map is only backdrop here; if it cannot be re-rendered the dialog still works.
            }
        }
        // The dialog then travels as one piece -- wash, card, rows, fields and buttons. Pushed after the
        // parent's own pass: that backdrop is another screen's frame, and translating it would slide the map
        // under the dialog and leave a strip of the parent's unexpanded dimming.
        UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));
        // A modal card is painted over a wash so the description is read against the card rather than
        // whatever the map draws. Oversized by SCRIM_MARGIN on every side, because the frame it is drawn in
        // is translated while the dialog arrives.
        graphics.fill(-UiMotion.SCRIM_MARGIN, -UiMotion.SCRIM_MARGIN,
                width + UiMotion.SCRIM_MARGIN, height + UiMotion.SCRIM_MARGIN,
                UiMotion.fade(UiTheme.dim(), open));
        HudDraw.fillRounded(graphics, cardX, cardY, cardX + cardW, cardY + cardH, CORNER_RADIUS,
                UiMotion.fade(UiTheme.panelSolid(), open));
        HudDraw.outlineRounded(graphics, cardX, cardY, cardX + cardW, cardY + cardH,
                CORNER_RADIUS, UiMotion.fade(UiTheme.panelEdge(), open));

        drawTitle(graphics);
        drawSource(graphics);
        drawAddress(graphics);
        if (showsLines()) {
            drawLines(graphics);
        }
        if (showsType()) {
            // The box itself draws in super.render below; only its caption is here, and it uses the word the
            // kind's section picks.
            graphics.drawString(font, caption(PlaceSections.labelKey(place.kind())), contentX,
                    categoryCaptionY, UiMotion.fade(UiTheme.header(),
                            UiMotion.stagger(ROW_TYPE, open)), false);
        }

        graphics.drawString(font, caption(DESCRIPTION_KEY), contentX, descriptionCaptionY,
                UiMotion.fade(UiTheme.header(), UiMotion.stagger(ROW_DESCRIPTION, open)), false);

        graphics.drawString(font, caption(TAGS_KEY), contentX, tagsCaptionY,
                UiMotion.fade(UiTheme.header(), UiMotion.stagger(ROW_TAGS, open)), false);
        drawTagsCounter(graphics);

        // The tags box is a widget and draws itself; the description and the favourite switch are drawn
        // here once the card under them exists.
        super.render(graphics, mouseX, mouseY, partialTick);
        description.render(graphics, this.font, descriptionFocused);
        drawFavorite(graphics, mouseX, mouseY);

        if (error != null) {
            drawError(graphics);
        }
        for (PanelButton button : buttons) {
            button.render(graphics, this.font, mouseX, mouseY);
        }
        // Last and unconditional: leaving the displacement pushed would misplace whatever the game draws
        // after the dialog.
        UiMotion.popFrame(graphics);
    }

    /**
     * The title row: the place's name on the left, the draft badge on the right when there is one. The
     * badge says that Done keeps the text rather than storing it.
     */
    private void drawTitle(GuiGraphics graphics) {
        String name = place.name();
        String heading = name == null || name.isBlank() ? title.getString() : name;
        float arrived = UiMotion.stagger(ROW_TITLE, open);
        int headingWidth = contentW;
        if (draft) {
            String badge = PlaceText.ellipsis(font, caption(DRAFT_KEY), Math.max(1, contentW / 3));
            int badgeWidth = font.width(badge);
            graphics.drawString(font, badge, contentX + contentW - badgeWidth, titleY,
                    UiMotion.fade(UiTheme.note(), arrived), false);
            headingWidth = Math.max(1, contentW - badgeWidth - GAP);
        }
        graphics.drawString(font, PlaceText.ellipsis(font, heading, Math.max(1, headingWidth)),
                contentX, titleY, UiMotion.fade(UiTheme.title(), arrived), false);
    }

    /**
     * The read-only source line: a caption, then the source as a pill. The value is never editable: it
     * is a property of the source, not of the place.
     */
    private void drawSource(GuiGraphics graphics) {
        float arrived = UiMotion.stagger(ROW_SOURCE, open);
        String sourceCaption = caption(SOURCE_KEY);
        graphics.drawString(font, sourceCaption, contentX, sourceY,
                UiMotion.fade(UiTheme.header(), arrived), false);
        String label = Destinations.sourceLabel(place.source());
        int labelX = contentX + font.width(sourceCaption) + GAP;
        int pillW = Math.max(0, Math.min(contentW - (labelX - contentX), font.width(label) + 8));
        if (pillW <= 0) {
            return;
        }
        HudDraw.fillRounded(graphics, labelX, sourceY - 1, labelX + pillW, sourceY + 9, 3,
                UiMotion.fade(UiTheme.chipSource(), arrived));
        graphics.drawString(font,
                PlaceText.ellipsis(font, label, Math.max(1, pillW - 4)), labelX + 4, sourceY,
                UiMotion.fade(UiTheme.onChip(), arrived), false);
    }

    /**
     * The read-only address row: where this place is in the road's words. The same sentence the card
     * draws, from the same cache, so opening the dialog costs no network search per frame and the two
     * views cannot describe one place two ways.
     */
    private void drawAddress(GuiGraphics graphics) {
        String line = PlaceAddressView.line(place.x(), place.z());
        graphics.drawString(font, PlaceText.ellipsis(font, line, contentW), contentX, addressY,
                UiMotion.fade(UiTheme.textSecondary(), UiMotion.stagger(ROW_ADDRESS, open)), false);
    }

    private void drawLines(GuiGraphics graphics) {
        List<TransitLine> lines = PlaceLines.at(place.x(), place.z());
        if (lines.isEmpty()) {
            return;
        }
        String lineCaption = caption(LINES_KEY);
        graphics.drawString(font, lineCaption, contentX, linesY,
                UiMotion.fade(UiTheme.header(), UiMotion.stagger(ROW_LINES, open)), false);
        int startX = contentX + font.width(lineCaption) + GAP;
        int limit = contentX + contentW;

        // How many chips fit, then take slots back until a "+N" for the rest also fits.
        int shown = 0;
        int width = 0;
        for (int i = 0; i < lines.size(); i++) {
            int chipWidth = font.width(lines.get(i).label()) + 8 + 3;
            if (startX + width + chipWidth > limit) {
                break;
            }
            width += chipWidth;
            shown++;
        }
        if (shown < lines.size()) {
            while (shown > 0) {
                int restWidth = font.width("+" + (lines.size() - shown)) + 8;
                if (startX + width + restWidth <= limit) {
                    break;
                }
                shown--;
                width -= font.width(lines.get(shown).label()) + 8 + 3;
            }
        }

        int chipX = startX;
        for (int i = 0; i < shown; i++) {
            TransitLine line = lines.get(i);
            String label = line.label();
            int chipWidth = Math.min(limit - chipX, font.width(label) + 8);
            if (chipWidth <= 0) {
                break;
            }
            // Each chip is a row of a very short list: it arrives with its row and one stagger step after the
            // chip before it, so the colours are read left to right.
            float arrived = UiMotion.stagger(ROW_LINES + i, open);
            int fill = line.colorOr(RoadConfig.transitLineColor(line.kind()));
            HudDraw.fillRounded(graphics, chipX, linesY - 1, chipX + chipWidth, linesY + 9, 3,
                    UiMotion.fade(fill, arrived));
            graphics.drawString(font, PlaceText.ellipsis(font, label, Math.max(1, chipWidth - 4)),
                    chipX + 4, linesY, UiMotion.fade(UiTheme.onChipFor(fill), arrived), false);
            chipX += chipWidth + 3;
        }
        if (shown < lines.size()) {
            String more = "+" + (lines.size() - shown);
            int restWidth = font.width(more) + 8;
            if (chipX + restWidth <= limit) {
                float arrived = UiMotion.stagger(ROW_LINES + shown, open);
                HudDraw.fillRounded(graphics, chipX, linesY - 1, chipX + restWidth, linesY + 9, 3,
                        UiMotion.fade(UiTheme.chipTag(), arrived));
                graphics.drawString(font, more, chipX + 4, linesY,
                        UiMotion.fade(UiTheme.onChip(), arrived), false);
            }
        }
    }

    private void drawError(GuiGraphics graphics) {
        float arrived = UiMotion.stagger(ROW_ERROR, open);
        List<String> lines = PlaceText.wrap(font, error.getString(), contentW);
        for (int i = 0; i < lines.size() && i < ERROR_LINES; i++) {
            String line = lines.get(i);
            if (i == ERROR_LINES - 1 && lines.size() > ERROR_LINES) {
                line = PlaceText.ellipsis(font, line, contentW);
            }
            graphics.drawString(font, line, contentX, errorY + i * ERROR_LINE_HEIGHT,
                    UiMotion.fade(UiTheme.error(), arrived), false);
        }
    }

    private static String caption(String key) {
        return Component.translatable(key).getString();
    }

    private void drawTagsCounter(GuiGraphics graphics) {
        String text = Component.translatable(TAGS_COUNT_KEY,
                String.valueOf(parseTags(tagsField.getValue()).size()),
                String.valueOf(PlaceDetails.MAX_TAGS)).getString();
        String shown = font.plainSubstrByWidth(text, Math.max(1, contentW / 2));
        graphics.drawString(font, shown, contentX + contentW - font.width(shown), tagsCaptionY,
                UiMotion.fade(UiTheme.textFaint(), UiMotion.stagger(ROW_TAGS, open)), false);
    }

    private void drawFavorite(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean hovered = insideFavorite(mouseX, mouseY);
        // How far the row has arrived with the card and how far the cursor has moved onto the switch, kept
        // separate so the row is readable at full strength once there.
        float arrived = UiMotion.stagger(ROW_FAVORITE, open);
        float lit = hovers.get(0).to(hovered ? 1.0F : 0.0F).eased();
        // A switched-on switch keeps its own face under the cursor: the fill is the answer, and a hover that
        // repainted it would hide the state just set.
        int face = favorite ? UiTheme.buttonOn()
                : UiMotion.mix(UiTheme.button(), UiTheme.buttonHover(), lit);
        HudDraw.fillRounded(graphics, contentX, favoriteY, contentX + contentW,
                favoriteY + TOGGLE_HEIGHT, 3, UiMotion.fade(face, arrived));
        HudDraw.drawRowMarker(graphics, contentX + 5, favoriteY + (TOGGLE_HEIGHT - TOGGLE_MARK) / 2,
                TOGGLE_MARK, UiMotion.fade(favorite ? UiTheme.accent() : UiTheme.icon(), arrived));
        String label = Component.translatable(favorite ? FAVORITED_KEY : FAVORITE_KEY).getString();
        String shown = font.plainSubstrByWidth(label, contentW - TOGGLE_MARK - 12);
        graphics.drawString(font, shown, contentX + TOGGLE_MARK + 8,
                favoriteY + (TOGGLE_HEIGHT - 8) / 2,
                UiMotion.fade(favorite ? UiTheme.onButtonOn() : UiTheme.onButton(), arrived), false);
    }

    private boolean insideFavorite(double mouseX, double mouseY) {
        return mouseX >= contentX && mouseX < contentX + contentW
                && mouseY >= favoriteY && mouseY < favoriteY + TOGGLE_HEIGHT;
    }
}
