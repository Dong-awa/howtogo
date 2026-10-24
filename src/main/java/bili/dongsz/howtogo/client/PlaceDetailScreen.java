package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.place.PlaceDetails;
import bili.dongsz.howtogo.route.Destination;
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
 *
 * <h2>Two ways in, one dialog</h2>
 * The same card is reached from two places that want different things done with the result, so the
 * difference is a constructor argument rather than a second screen:
 *
 * <ul>
 *   <li>{@link #openForStore} is the read-and-edit dialog of a place somebody already has -- the
 *       details card of the destination picker and of the main screen. Its Done writes the store
 *       directly, because there is nothing else that could apply the change afterwards.</li>
 *   <li>{@link #openForDraft} is the draft editor the place edit panel opens from its More… button.
 *       Its Done hands the record back and writes nothing: the place panel's Done is the one thing
 *       that commits an edit, and a dialog that saved by itself would make the panel's Cancel a lie
 *       about having cancelled everything.</li>
 * </ul>
 *
 * <p>The card is drawn over a re-render of the parent rather than as a screen of its own. That is the
 * shape {@link RoadNameScreen} uses, and for the same reason: the map underneath is the context for
 * what is being described, and a full-screen sheet would have replaced it.
 *
 * <h2>Why the description is a hand-drawn field</h2>
 * {@link MultilineField} exists because neither {@code EditBox} (one line) nor
 * {@code MultiLineEditBox} (absent before 1.20.5) can be used on every node of this tree. The tags
 * stay a vanilla {@code EditBox}: one line of comma-separated words is exactly what it is for, and
 * the platform input method comes with it.
 *
 * <h2>Why the limits are checked here instead of trusted to the store</h2>
 * The store normalises, which loses text silently -- the part over the limit simply is not there when
 * the player reopens the dialog. Refusing with {@link #OVER_LIMIT_KEY} and staying open is the
 * difference between "that was too long, shorten it" and "what I wrote is gone".
 */
public final class PlaceDetailScreen extends Screen {

    /**
     * The dialog's own name: this is the editor, not the read-only card, so it says "edit".
     *
     * <p>Also the heading for a place that has no name yet: a place dropped with P is nameless until
     * it is named, and an empty title line would look like a failure rather than like the place whose
     * name the player is about to type.
     */
    public static final String TITLE_KEY = "screen.howtogo.place_details.edit_title";
    /** The caption before the read-only source, whose value is drawn beside it. */
    public static final String SOURCE_KEY = "screen.howtogo.place_details.source";
    /** The caption over the description box. */
    public static final String DESCRIPTION_KEY = "screen.howtogo.place_details.description";
    /** The greyed placeholder of an empty description. */
    public static final String DESCRIPTION_HINT_KEY = "screen.howtogo.place_details.description_hint";
    /** The caption over the tags box. */
    public static final String TAGS_KEY = "screen.howtogo.place_details.tags";
    /** The hint in the tag box: how the words are separated. */
    public static final String TAGS_HINT_KEY = "screen.howtogo.place_details.tags_hint";
    /**
     * "Tags %s/%s" -- the used-of-allowed counter beside the tags caption.
     *
     * <p>The description needs none: the box itself refuses to take a 513th character, so a counter
     * over it would only ever say the same number until it stopped moving.
     */
    public static final String TAGS_COUNT_KEY = "screen.howtogo.place_details.tags_count";
    /** The favourite switch, off. */
    public static final String FAVORITE_KEY = "screen.howtogo.place_details.favorite";
    /** The favourite switch, on. */
    public static final String FAVORITED_KEY = "screen.howtogo.place_details.favorited";
    /** Marks the draft mode: what is written here is not stored until the place panel's Done. */
    public static final String DRAFT_KEY = "screen.howtogo.place_details.draft";
    /** The refusal when the typed text is longer than a limit allows. */
    public static final String OVER_LIMIT_KEY = "screen.howtogo.place_details.over_limit";
    /**
     * The button in the place edit panel that opens this dialog as a draft.
     *
     * <p>Declared here rather than in {@link RoadNameScreen} because this dialog owns the words about
     * details: a second key for the same action would be the first step towards the two drifting.
     */
    public static final String MORE_KEY = "screen.howtogo.place_details.more";

    /**
     * How the typed tag text is cut into tags: every comma a Chinese or a western keyboard can send,
     * the two Oriental semicolons, and any whitespace.
     *
     * <p>Generous on purpose. This field is typed on phones with Chinese input methods as often as on
     * a desktop keyboard, and a player who separates tags with a space where the hint said comma
     * should get their tags, not one long tag.
     */
    private static final String TAG_SEPARATORS = "[,，;；、\\s]+";

    private static final int PAD = 8;
    private static final int GAP = 6;
    /** The card never fills the window: it is a dialog over the map, not a replacement for it. */
    private static final int CARD_MAX_WIDTH = 320;
    private static final int CARD_MIN_WIDTH = 200;
    private static final int TITLE_HEIGHT = 11;
    private static final int SOURCE_HEIGHT = 10;
    private static final int CAPTION_HEIGHT = 9;
    /** The description box gives way first on a short window, but never below this. */
    private static final int DESCRIPTION_MIN_HEIGHT = 40;
    /** The absolute floor, for a window shorter than the card's fixed rows. One line, clipped. */
    private static final int DESCRIPTION_CRAMPED_HEIGHT = 20;
    private static final int DESCRIPTION_MAX_HEIGHT = 90;
    private static final int TAG_FIELD_HEIGHT = 16;
    private static final int TOGGLE_HEIGHT = 16;
    /**
     * The error line: two lines' worth, reserved whether or not there is a refusal on it.
     *
     * <p>Two because the limits message names three numbers and does not fit one line of the card's
     * narrowest width in either language -- and an explanation cut off halfway is worse than none,
     * since it reads as the dialog being broken. Reserved rather than grown so the card does not jump
     * when a refusal appears.
     */
    private static final int ERROR_LINES = 2;
    private static final int ERROR_LINE_HEIGHT = 10;
    private static final int ERROR_HEIGHT = ERROR_LINES * ERROR_LINE_HEIGHT;
    private static final int BUTTON_HEIGHT = 16;
    private static final int CORNER_RADIUS = 5;
    /** The marker square that makes the favourite switch readable as a switch, not a word. */
    private static final int TOGGLE_MARK = 6;

    private final Screen parent;
    private final Destination place;
    /** What Done does with the record: the store, or the caller that opened this as a draft. */
    private final Consumer<PlaceDetails> onAccept;
    /**
     * Whether the record being edited is a draft that nothing will store yet.
     *
     * <p>Only the title row uses it -- a badge saying so -- but it is a field because the difference
     * matters to the player: this dialog's Done keeps the text, and the place panel's Done is what
     * commits it. Without the badge, cancelling the panel after writing a long description would look
     * like a lost save rather than like a cancelled edit.
     */
    private final boolean draft;
    /** The description field, kept across {@link #init()} so a resize does not lose the text. */
    private final MultilineField description;

    private boolean favorite;

    /**
     * Whether the description has the focus.
     *
     * <p>This field is not a widget, so the screen's own focus cannot describe it: the vanilla focus
     * is either the tags box or {@code null}, and this flag says what {@code null} means. Kept as a
     * field rather than derived so a resize (which rebuilds the widgets) does not move the typing.
     */
    private boolean descriptionFocused = true;

    private EditBox tagsField;
    /** The tag text, carried through {@link #init()} so a resize does not lose it either. */
    private String tagsText;

    /** The message shown when the last attempt was refused, or null. */
    private Component error;

    /** Done and cancel, built in {@link #init()} and drawn and hit-tested by hand. */
    private List<PanelButton> buttons = List.of();

    private int cardX;
    private int cardY;
    private int cardW;
    private int cardH;
    private int contentX;
    private int contentW;
    private int titleY;
    private int sourceY;
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
    }

    /**
     * Opens the dialog for a place that already exists; Done writes {@link PlaceDetailStore}.
     *
     * <p>The write is direct because this path has no other commit point: the caller is a details
     * card that is still open underneath, and it redraws from the store on the next frame.
     */
    public static void openForStore(Screen parent, Destination place) {
        if (place == null) {
            return;
        }
        PlaceDetails initial = PlaceDetailStore.detailsOf(place);
        Screens.set(new PlaceDetailScreen(parent, place, initial,
                details -> PlaceDetailStore.put(place, details), false));
    }

    /**
     * Opens the dialog as a draft: Done hands the record to {@code onAccept} and writes nothing.
     *
     * <p>Used by the place edit panel, whose own Done is the single commit of a place edit -- name,
     * kind and details together. Its Cancel therefore drops the whole edit, and this dialog's Cancel
     * drops only the details, which is what the two buttons have to mean for the pair to be honest.
     */
    public static void openForDraft(Screen parent, Destination place, PlaceDetails initial,
                                    Consumer<PlaceDetails> onAccept) {
        if (place == null || onAccept == null) {
            return;
        }
        Screens.set(new PlaceDetailScreen(parent, place, initial, onAccept, true));
    }

    /**
     * Keep the world running behind the dialog, like every other panel this mod opens.
     *
     * <p>Writing a description is not worth freezing a trip for, and the screens this one opens over
     * -- the picker, the map -- have already made the same choice. Nothing here is
     * multiplayer-synchronised, so the host is not waiting on it either.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        cardW = Math.min(CARD_MAX_WIDTH, Math.max(CARD_MIN_WIDTH, width - 16));
        contentW = cardW - PAD * 2;

        // The card's height is the fixed rows plus whatever the window can spare for the description,
        // inside the description's own bounds. Measured first so `cardY` can centre the card before
        // any row is placed -- the rows are then placed from the card down, and cannot disagree with
        // the height that was used to centre it.
        int fixed = PAD + TITLE_HEIGHT + 2 + SOURCE_HEIGHT + GAP
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
            // A window too short even for the minimum box: the description gives up the rest rather
            // than pushing the buttons off the bottom. Minecraft's own smallest GUI is taller than
            // this, so it is a guard against a resized window rather than a normal layout.
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

        // The tag text is carried across the rebuild: this method runs again on a window resize, and
        // re-reading the old box is the only way the half-typed words survive it.
        String seed = tagsField != null ? tagsField.getValue() : tagsText;
        tagsField = new EditBox(this.font, contentX, tagsY, contentW, TAG_FIELD_HEIGHT,
                Component.translatable(TAGS_KEY));
        tagsField.setHint(Component.translatable(TAGS_HINT_KEY));
        // A tag is at most eight characters and there are at most sixteen; the box takes the room
        // those need with their separators, so the field refuses nothing the dialog would accept.
        tagsField.setMaxLength(PlaceDetails.MAX_TAGS * (PlaceDetails.MAX_TAG_LENGTH + 1));
        tagsField.setValue(seed == null ? "" : seed);
        addRenderableWidget(tagsField);
        if (descriptionFocused) {
            // The description is why the dialog exists and is all the player came to write; the tags
            // box is a Tab away, which is what the key handler below is for.
            setFocused(null);
        } else {
            setFocused(tagsField);
        }

        int half = (contentW - GAP) / 2;
        // Done is the action the dialog exists for, so it takes the accent; cancel is ordinary. Both
        // are this mod's own buttons rather than the game's grey widgets, like every other panel here.
        buttons = List.of(
                new PanelButton(contentX, buttonY, half, BUTTON_HEIGHT,
                        Component.translatable("gui.done"), PanelButton.Kind.PRIMARY, this::accept),
                new PanelButton(contentX + half + GAP, buttonY, contentW - half - GAP, BUTTON_HEIGHT,
                        Component.translatable("gui.cancel"), PanelButton.Kind.NEUTRAL, this::onClose));
    }

    // ---------------------------------------------------------------- accepting

    /**
     * Checks the raw text against the limits, then normalises and hands the record over.
     *
     * <p>The check is on what was typed, before {@link PlaceDetails#normalize()} -- which trims and
     * truncates. Checking after normalising would always pass, and the text that was cut away would
     * disappear without a word; see the class comment.
     */
    private void accept() {
        // The last refusal was about the text as it stood then; every attempt starts clean, so a
        // message cannot outlive the reason for it while the player fixes the field it named.
        error = null;
        List<String> typedTags = parseTags(tagsField.getValue());
        boolean overLimit = PlaceDetails.lengthInCodePoints(description.value())
                > PlaceDetails.MAX_DESCRIPTION
                || typedTags.size() > PlaceDetails.MAX_TAGS;
        if (!overLimit) {
            for (String tag : typedTags) {
                if (PlaceDetails.lengthInCodePoints(tag) > PlaceDetails.MAX_TAG_LENGTH) {
                    overLimit = true;
                    break;
                }
            }
        }
        if (overLimit) {
            error = Component.translatable(OVER_LIMIT_KEY, PlaceDetails.MAX_DESCRIPTION,
                    PlaceDetails.MAX_TAGS, PlaceDetails.MAX_TAG_LENGTH);
            return;
        }
        onAccept.accept(PlaceDetails.of(description.value(), typedTags, favorite).normalize());
        onClose();
    }

    /**
     * The typed tag text cut into tags, blanks dropped and nothing yet trimmed or de-duplicated.
     *
     * <p>The raw pieces rather than a cleaned list: the limit checks and the counter above the box
     * have to describe what the player typed, and the store does the trimming and de-duplication
     * itself on the way in.
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

    /**
     * The stored tags as one editable line: what the tag box starts with.
     *
     * <p>Joined with the same kind of comma the hint tells the player to type, and no space: the box
     * is narrow, and the parser accepts both separators so nothing depends on which one this picks.
     */
    private static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        return String.join(",", tags);
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
        if (button == 0) {
            // The drawn buttons first: they are not widgets, so this is their only chance at the click,
            // and a press on one must not also move the caret under it.
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
                // The custom field becomes the focused thing, which on a plain Screen means the screen
                // has no focused widget: the flag above is what {@link #charTyped} reads.
                descriptionFocused = true;
                setFocused(null);
                description.mouseClicked(this.font, mouseX, mouseY);
                return true;
            }
            // Anywhere else leaves the description; a click on the tags box is handled by the widget
            // itself when the super call below offers it the click.
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
    // 1.21.9 made key input a record and moved the key codes onto the SDL-backed InputConstants. The
    // dispatch compares the code against a handful of constants, so the record is unpacked on entry
    // and the body below reads the same either way -- the same unpacking RoadNameScreen does.
    //? if >=1.21.9 {
    /*public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key();
    *///?} else {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    //?}
        // Tab is the only way the two fields trade the focus: a plain Screen has no tab order, and
        // without this the description -- which is not a widget -- could never be reached from the
        // widgets at all, and the tags box could never be left.
        if (keyCode == InputConstants.KEY_TAB) {
            descriptionFocused = !descriptionFocused;
            setFocused(descriptionFocused ? null : tagsField);
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

    /**
     * Sends committed characters to the focused field.
     *
     * <p>The custom description field cannot receive them any other way: it is not a widget, so the
     * game has nowhere to deliver a character except this screen. That is the reason the field is fed
     * from here rather than from key codes -- see {@link MultilineField}, whose class comment is the
     * long form of it.
     */
    @Override
    // 1.21.9 bundled committed characters into CharacterEvent, the same input-record change that
    // renamed the key and mouse events: the code point and the modifiers used to arrive as two
    // arguments and are one record now. The field takes an int either way, so only the signature and
    // the unpacking differ.
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
    // 1.20.2 split the wheel delta into a horizontal and a vertical component; 1.20.1 passes one.
    // Every use in this mod reads the vertical one, so only the signature and the super call differ.
    //? if >=1.20.2 {
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    //?} else {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
    *///?}
        if (description.contains(mouseX, mouseY)) {
            // Consumed even when the text is short enough not to scroll, so the wheel over the card
            // does not leak into whatever is underneath it.
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

    /** No dim, no blur: the map underneath is the context for the place being described. */
    @Override
    // 1.20.2 added the mouse position and the partial tick to this override; 1.20.1 takes only the
    // graphics. Both do nothing here -- the dimming is handled in render so the card sits between it
    // and the widgets -- so the bodies are the same and only the signature differs.
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}
    }

    // ------------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (parent != null) {
            try {
                parent.render(graphics, mouseX, mouseY, partialTick);
            } catch (Throwable t) {
                // The map is only backdrop here; if it cannot be re-rendered the dialog still works.
            }
        }
        // A modal card is painted over a wash rather than straight over the map: the description is a
        // paragraph of text, and it has to be read against the card and not against whatever the map
        // happens to be drawing under that column.
        graphics.fill(0, 0, width, height, UiTheme.dim());
        HudDraw.fillRounded(graphics, cardX, cardY, cardX + cardW, cardY + cardH, CORNER_RADIUS,
                UiTheme.panelSolid());
        HudDraw.outlineRounded(graphics, cardX, cardY, cardX + cardW, cardY + cardH,
                CORNER_RADIUS, UiTheme.panelEdge());

        drawTitle(graphics);
        drawSource(graphics);

        graphics.drawString(font, caption(DESCRIPTION_KEY), contentX, descriptionCaptionY,
                UiTheme.header(), false);

        graphics.drawString(font, caption(TAGS_KEY), contentX, tagsCaptionY, UiTheme.header(), false);
        drawTagsCounter(graphics);

        // The tags box is a widget, so it draws itself; the description and the favourite switch do
        // not, and are drawn here once the card under them exists.
        super.render(graphics, mouseX, mouseY, partialTick);
        description.render(graphics, this.font, descriptionFocused);
        drawFavorite(graphics, mouseX, mouseY);

        if (error != null) {
            drawError(graphics);
        }
        // The buttons last: everything above them is this class's own drawing, and a button painted
        // before it could have a line of it across its face.
        for (PanelButton button : buttons) {
            button.render(graphics, this.font, mouseX, mouseY);
        }
    }

    /**
     * The title row: the place's name on the left, and the draft badge on the right when there is one.
     *
     * <p>The badge is what tells the player that Done here keeps the text rather than storing it: the
     * one thing this dialog does differently in its two modes, and the one thing a player would
     * otherwise have to guess.
     */
    private void drawTitle(GuiGraphics graphics) {
        String name = place.name();
        String heading = name == null || name.isBlank() ? title.getString() : name;
        int headingWidth = contentW;
        if (draft) {
            String badge = PlaceText.ellipsis(font, caption(DRAFT_KEY), Math.max(1, contentW / 3));
            int badgeWidth = font.width(badge);
            graphics.drawString(font, badge, contentX + contentW - badgeWidth, titleY,
                    UiTheme.note(), false);
            headingWidth = Math.max(1, contentW - badgeWidth - GAP);
        }
        graphics.drawString(font, PlaceText.ellipsis(font, heading, Math.max(1, headingWidth)),
                contentX, titleY, UiTheme.title(), false);
    }

    /**
     * The read-only source line: a caption, then the source as a pill.
     *
     * <p>The pill is the details card's own mark for a source, reused here so the dialog and the card
     * it was opened from say where the place came from in the same shape. The value is never editable:
     * it is a property of the source, not of the place.
     */
    private void drawSource(GuiGraphics graphics) {
        String sourceCaption = caption(SOURCE_KEY);
        graphics.drawString(font, sourceCaption, contentX, sourceY, UiTheme.header(), false);
        String label = Destinations.sourceLabel(place.source());
        int labelX = contentX + font.width(sourceCaption) + GAP;
        int pillW = Math.max(0, Math.min(contentW - (labelX - contentX), font.width(label) + 8));
        if (pillW <= 0) {
            return;
        }
        HudDraw.fillRounded(graphics, labelX, sourceY - 1, labelX + pillW, sourceY + 9, 3,
                UiTheme.selection());
        graphics.drawString(font,
                PlaceText.ellipsis(font, label, Math.max(1, pillW - 4)), labelX + 4, sourceY,
                UiTheme.onSelection(), false);
    }

    /**
     * The refusal, wrapped over its reserved lines.
     *
     * <p>The message names three numbers, so it does not fit one line at the card's narrowest; the
     * second line is there for exactly that. More text than two lines gets an ellipsis rather than
     * being dropped silently, which is the same rule the limits themselves follow.
     */
    private void drawError(GuiGraphics graphics) {
        List<String> lines = PlaceText.wrap(font, error.getString(), contentW);
        for (int i = 0; i < lines.size() && i < ERROR_LINES; i++) {
            String line = lines.get(i);
            if (i == ERROR_LINES - 1 && lines.size() > ERROR_LINES) {
                line = PlaceText.ellipsis(font, line, contentW);
            }
            graphics.drawString(font, line, contentX, errorY + i * ERROR_LINE_HEIGHT,
                    UiTheme.error(), false);
        }
    }

    /** A caption's text; the keys are constants so a typo cannot hide a translation. */
    private static String caption(String key) {
        return Component.translatable(key).getString();
    }

    /**
     * The used-of-allowed counter at the far right of the tags caption.
     *
     * <p>The tag count is the one the player can push past a limit by hand -- the description box
     * refuses a character past its own limit -- so it is the one that needs saying before Done has to
     * refuse.
     */
    private void drawTagsCounter(GuiGraphics graphics) {
        String text = Component.translatable(TAGS_COUNT_KEY,
                String.valueOf(parseTags(tagsField.getValue()).size()),
                String.valueOf(PlaceDetails.MAX_TAGS)).getString();
        String shown = font.plainSubstrByWidth(text, Math.max(1, contentW / 2));
        graphics.drawString(font, shown, contentX + contentW - font.width(shown), tagsCaptionY,
                UiTheme.textFaint(), false);
    }

    /**
     * The favourite switch: a chip whose face changes with its state, with a marker square at its left.
     *
     * <p>A switch rather than a tick box because it is one of three fields on a card whose other two
     * are boxes: a row that changes colour when pressed reads as a control without having to look like
     * a form. The marker carries the state for a player who cannot tell the two faces apart -- the
     * accent fill is the answer, and the label repeats it in words.
     */
    private void drawFavorite(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean hovered = insideFavorite(mouseX, mouseY);
        int face = favorite ? UiTheme.buttonOn()
                : (hovered ? UiTheme.buttonHover() : UiTheme.button());
        HudDraw.fillRounded(graphics, contentX, favoriteY, contentX + contentW,
                favoriteY + TOGGLE_HEIGHT, 3, face);
        HudDraw.drawRowMarker(graphics, contentX + 5, favoriteY + (TOGGLE_HEIGHT - TOGGLE_MARK) / 2,
                TOGGLE_MARK, favorite ? UiTheme.accent() : UiTheme.icon());
        String label = Component.translatable(favorite ? FAVORITED_KEY : FAVORITE_KEY).getString();
        String shown = font.plainSubstrByWidth(label, contentW - TOGGLE_MARK - 12);
        graphics.drawString(font, shown, contentX + TOGGLE_MARK + 8,
                favoriteY + (TOGGLE_HEIGHT - 8) / 2,
                favorite ? UiTheme.onButtonOn() : UiTheme.onButton(), false);
    }

    private boolean insideFavorite(double mouseX, double mouseY) {
        return mouseX >= contentX && mouseX < contentX + contentW
                && mouseY >= favoriteY && mouseY < favoriteY + TOGGLE_HEIGHT;
    }
}
