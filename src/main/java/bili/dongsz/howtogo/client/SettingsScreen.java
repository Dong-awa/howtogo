package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.route.TravelMode;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
// 1.21.9 bundled mouse coordinates and the button into a record.
//? if >=1.21.9 {
/*import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
*///?}
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * The mod's in-game settings: the client config's player-facing half, editable without a file.
 *
 * <h2>What is on it, and what is not</h2>
 * The config file holds every tuning value this mod has, from per-class road colours to the browser
 * map's port. Most of them are answers to questions a player does not ask -- how wide a road has to
 * be drawn before it counts as the one underfoot, how many chunks a scan may read a second -- and a
 * screen listing all of them would be a second config file with a scroll bar. This screen carries
 * the settings a player actually reaches for: how the interface looks, where navigation is drawn,
 * how trips fall back, and which optional map layers are read. The rest stay in the file, and the
 * class's {@code ROWS} list is where the line is drawn.
 *
 * <h2>Why it is drawn rather than built from widgets</h2>
 * Every other screen this mod opens is drawn by hand in {@link UiTheme}'s colours; a column of
 * vanilla sliders would be the one part of the interface that did not belong to either theme. The
 * controls here are therefore the mod's own: a rounded toggle, a chip that cycles a short list, a
 * {@code - value +} stepper, and plain {@link PanelButton}s in the footer. A slider is deliberately
 * absent: the numeric settings are coordinates and distances where a step is more useful than a
 * drag, and a track would have to invent a colour role for its own knob.
 *
 * <h2>Saving</h2>
 * Every edit is written and saved at once, through {@link RoadConfig}'s typed setters. There is no
 * "apply" button and no close-time save: a player who changes something and then presses Escape has
 * already persisted it. The one line that matters is {@link #commit()}, which every control runs.
 *
 * <h2>Theme</h2>
 * No colour is written down here. Every surface, edge, text rank, control face and state comes from
 * {@link UiTheme}, which resolves the palette on each call -- so the theme row changes the whole
 * screen on the next frame, and a theme that switches with the time of day recolours this screen
 * while it is open.
 */
final class SettingsScreen extends Screen {

    private static final int PAD = 10;
    private static final int GAP = 6;
    private static final int TITLE_HEIGHT = 16;
    private static final int LINE_HEIGHT = 10;
    private static final int BUTTON_HEIGHT = 18;
    private static final int ROW_HEIGHT = 30;
    private static final int GROUP_HEIGHT = 18;
    private static final int CONTROL_HEIGHT = 14;
    private static final int TOGGLE_WIDTH = 46;
    private static final int STEP_BOX = 16;
    private static final int STEP_VALUE_ROOM = 52;
    private static final int DESC_LINES = 3;
    private static final int SCROLL_STEP = 18;
    /** How long a "saved" acknowledgement stays in the description strip. */
    private static final long STATUS_MILLIS = 2500L;

    /** The screen to go back to: the main menu this was opened from. */
    private final Screen parent;

    private final List<Group> groups = new ArrayList<>();
    /** Every row in drawing order, so the hit test and the reset walk use one list. */
    private final List<Row> rows = new ArrayList<>();
    private final List<PanelButton> buttons = new ArrayList<>();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int descY;
    private int contentHeight;
    private int scroll;

    /** The reset button's first press arms it; the second one restores the defaults. */
    private boolean resetArmed;
    private Component status;
    private long statusUntil;
    /** Whether the window is tall enough to hold the description strip; see {@link #init()}. */
    private boolean descriptionVisible;
    /** The cursor position the current frame was drawn with; only the stepper boxes read it. */
    private double pointerX;
    private double pointerY;

    SettingsScreen(Screen parent) {
        super(Component.translatable("screen.howtogo.settings"));
        this.parent = parent;
        buildRows();
    }

    @Override
    public boolean isPauseScreen() {
        // Like the mod's other windows: the world keeps running behind it.
        return false;
    }

    // ------------------------------------------------------------------ content

    /** The settings this screen exposes, in the order they are read and reset. */
    private void buildRows() {
        List<UiTheme.Mode> themes = List.of(UiTheme.Mode.values());
        List<MapDisplayMode> displayModes = List.of(MapDisplayMode.values());
        List<HudAnchor> anchors = List.of(HudAnchor.values());
        List<TravelMode> travelModes = List.of(TravelMode.values());

        group("screen.howtogo.settings.group.appearance",
                Row.choice("howtogo.configuration.ui_theme",
                        "screen.howtogo.settings.ui_theme.desc",
                        List.of(label("screen.howtogo.settings.theme.dark"),
                                label("screen.howtogo.settings.theme.light"),
                                label("screen.howtogo.settings.theme.auto")),
                        () -> themes.indexOf(RoadConfig.uiTheme()),
                        index -> RoadConfig.setUiTheme(themes.get(index)),
                        themes.indexOf(UiTheme.Mode.AUTO)),
                Row.choice("howtogo.configuration.map_display_mode",
                        "screen.howtogo.settings.map_display_mode.desc",
                        List.of(label("howtogo.configuration.map_display_mode.hud"),
                                label("howtogo.configuration.map_display_mode.xaero_minimap")),
                        () -> displayModes.indexOf(RoadConfig.mapDisplayMode()),
                        index -> RoadConfig.setMapDisplayMode(displayModes.get(index)),
                        displayModes.indexOf(MapDisplayMode.defaultMode())));

        // The compact HUD card only exists while the map half is actually drawn on Xaero's Minimap,
        // so its three rows follow the effective mode rather than the chosen one: choosing Xaero's
        // Minimap on an install that cannot provide it falls back to the HUD panel, and the rows
        // would otherwise look usable while changing nothing.
        BooleanSupplier xaeroMode = () -> NavMapMode.effective() == MapDisplayMode.XAERO_MINIMAP;
        Component needsXaero = label("screen.howtogo.settings.needs_xaero");
        group("screen.howtogo.settings.group.hud",
                Row.choice("howtogo.configuration.compact_hud_anchor",
                        "screen.howtogo.settings.compact_hud_anchor.desc",
                        List.of(label("howtogo.configuration.compact_hud_anchor.top_left"),
                                label("howtogo.configuration.compact_hud_anchor.top_right"),
                                label("howtogo.configuration.compact_hud_anchor.bottom_left"),
                                label("howtogo.configuration.compact_hud_anchor.bottom_right")),
                        () -> anchors.indexOf(RoadConfig.compactHudAnchor()),
                        index -> RoadConfig.setCompactHudAnchor(anchors.get(index)),
                        anchors.indexOf(HudAnchor.defaultAnchor()))
                        .onlyWhen(xaeroMode, needsXaero),
                Row.number("howtogo.configuration.compact_hud_offset_x",
                        "screen.howtogo.settings.compact_hud_offset_x.desc",
                        RoadConfig::compactHudOffsetX,
                        value -> RoadConfig.setCompactHudOffsetX((int) value),
                        -200, 200, 5, 0, true)
                        .onlyWhen(xaeroMode, needsXaero),
                Row.number("howtogo.configuration.compact_hud_offset_y",
                        "screen.howtogo.settings.compact_hud_offset_y.desc",
                        RoadConfig::compactHudOffsetY,
                        value -> RoadConfig.setCompactHudOffsetY((int) value),
                        -200, 200, 5, 0, true)
                        .onlyWhen(xaeroMode, needsXaero));

        group("screen.howtogo.settings.group.navigation",
                Row.choice("howtogo.configuration.default_travel_mode",
                        "screen.howtogo.settings.default_travel_mode.desc",
                        List.of(travelModeLabel(TravelMode.WALK),
                                travelModeLabel(TravelMode.DRIVE),
                                travelModeLabel(TravelMode.TRANSIT)),
                        () -> travelModes.indexOf(RoadConfig.defaultTravelMode() == null
                                ? TravelMode.WALK : RoadConfig.defaultTravelMode()),
                        index -> RoadConfig.setDefaultTravelMode(travelModes.get(index)),
                        travelModes.indexOf(TravelMode.WALK)),
                Row.toggle("howtogo.configuration.fall_back_to_walking_when_slower",
                        "screen.howtogo.settings.fall_back_to_walking_when_slower.desc",
                        RoadConfig::fallBackToWalkingWhenSlower,
                        RoadConfig::setFallBackToWalkingWhenSlower, true),
                Row.number("howtogo.configuration.transit_wait_seconds",
                        "screen.howtogo.settings.transit_wait_seconds.desc",
                        RoadConfig::transitWaitSeconds,
                        RoadConfig::setTransitWaitSeconds,
                        0, 3600, 10, 60, true),
                Row.number("howtogo.configuration.walk_offroad_blocks",
                        "screen.howtogo.settings.walk_offroad_blocks.desc",
                        RoadConfig::walkOffroadBlocks,
                        RoadConfig::setWalkOffroadBlocks,
                        0, 4096, 8, 32, true),
                Row.number("howtogo.configuration.station_snap_blocks",
                        "screen.howtogo.settings.station_snap_blocks.desc",
                        RoadConfig::stationSnapBlocks,
                        RoadConfig::setStationSnapBlocks,
                        1, 256, 4, 32, true));

        // MTR's whole-map reader is only consulted while MTR data is read at all; the row stays
        // visible and greyed, because the switch above it is the explanation.
        BooleanSupplier mtrMode = RoadConfig::mtrTransit;
        Component needsMtr = label("screen.howtogo.settings.needs_mtr");
        group("screen.howtogo.settings.group.sources",
                Row.toggle("howtogo.configuration.create_train_tracks",
                        "screen.howtogo.settings.create_train_tracks.desc",
                        RoadConfig::createTrainTracks,
                        RoadConfig::setCreateTrainTracks, true),
                Row.toggle("howtogo.configuration.mtr_transit",
                        "screen.howtogo.settings.mtr_transit.desc",
                        RoadConfig::mtrTransit,
                        RoadConfig::setMtrTransit, true),
                Row.toggle("howtogo.configuration.mtr_full_map",
                        "screen.howtogo.settings.mtr_full_map.desc",
                        RoadConfig::mtrFullMap,
                        RoadConfig::setMtrFullMap, true)
                        .onlyWhen(mtrMode, needsMtr),
                Row.toggle("howtogo.configuration.roadweaver_roads",
                        "screen.howtogo.settings.roadweaver_roads.desc",
                        RoadConfig::roadWeaverRoads,
                        RoadConfig::setRoadWeaverRoads, true));

        group("screen.howtogo.settings.group.webmap",
                Row.toggle("howtogo.configuration.webmap_auto_start",
                        "screen.howtogo.settings.webmap_auto_start.desc",
                        RoadConfig::webMapAutoStart,
                        RoadConfig::setWebMapAutoStart, false));
    }

    private void group(String titleKey, Row... members) {
        groups.add(new Group(label(titleKey), List.of(members)));
        rows.addAll(List.of(members));
    }

    private static Component label(String key) {
        return Component.translatable(key);
    }

    private static Component travelModeLabel(TravelMode mode) {
        return switch (mode) {
            case WALK -> label("hud.howtogo.mode.walk");
            case DRIVE -> label("hud.howtogo.mode.drive");
            case TRANSIT -> label("hud.howtogo.mode.transit");
        };
    }

    // ------------------------------------------------------------------- layout

    @Override
    protected void init() {
        panelW = Math.min(480, this.width - 16);
        panelH = Math.min(400, this.height - 16);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int footerY = panelY + panelH - PAD - BUTTON_HEIGHT;
        int descBottom = footerY - GAP;
        descY = descBottom - LINE_HEIGHT * DESC_LINES;
        listX = panelX + PAD;
        listW = panelW - PAD * 2;
        listY = panelY + PAD + TITLE_HEIGHT + 8;
        listH = Math.max(20, descY - GAP - listY);
        // A window shorter than the three bands cannot hold the description strip as well; the list
        // keeps its room and the strip is omitted rather than painted over it.
        descriptionVisible = descY - GAP - listY >= ROW_HEIGHT;

        measureContent();
        scroll = clampScroll(scroll);
        buildButtons();
    }

    /** Puts every row's top edge into content coordinates: the same numbers draw and hit-test. */
    private void measureContent() {
        int y = 0;
        for (Group group : groups) {
            group.contentY = y;
            y += GROUP_HEIGHT;
            for (Row row : group.rows) {
                row.contentY = y;
                y += ROW_HEIGHT;
            }
            y += GAP;
        }
        // The gap is between groups, so the last one does not leave a blank strip at the end of the
        // scroll range.
        contentHeight = Math.max(0, y - GAP);
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - listH);
    }

    private int clampScroll(int value) {
        return Math.max(0, Math.min(maxScroll(), value));
    }

    private void buildButtons() {
        buttons.clear();
        int inner = panelW - PAD * 2;
        int half = (inner - GAP) / 2;
        int footerY = panelY + panelH - PAD - BUTTON_HEIGHT;
        buttons.add(new PanelButton(panelX + PAD, footerY, half, BUTTON_HEIGHT,
                Component.translatable(resetArmed
                        ? "screen.howtogo.settings.reset_confirm"
                        : "screen.howtogo.settings.reset"),
                resetArmed ? PanelButton.Kind.DANGER : PanelButton.Kind.NEUTRAL, this::resetClicked));
        buttons.add(new PanelButton(panelX + PAD + half + GAP, footerY, half, BUTTON_HEIGHT,
                Component.translatable("screen.howtogo.settings.back"),
                PanelButton.Kind.NEUTRAL, this::onClose));
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
            for (PanelButton candidate : buttons) {
                if (candidate.click(mouseX, mouseY)) {
                    return true;
                }
            }
            // A press anywhere else takes the reset button out of its armed state: the second press
            // has to be a second press on the same control, not the next click in the screen, so the
            // press that disarms it is consumed rather than also editing the row under it.
            if (resetArmed) {
                resetArmed = false;
                buildButtons();
                if (insideList(mouseX, mouseY)) {
                    return true;
                }
            }
            if (insideList(mouseX, mouseY)) {
                Row row = rowAt(mouseX, mouseY);
                if (row != null && row.isEnabled()) {
                    int rowY = listY + row.contentY - scroll;
                    int controlX = controlX(row);
                    int controlY = controlY(rowY);
                    int width = controlWidth(row);
                    // The controls are what the player sees as clickable, so the hit test is the
                    // control's own rectangle -- not the whole row, whose label and description are
                    // text. This is the same rule DestinationScreen's option rows use.
                    if (mouseX >= controlX && mouseX < controlX + width
                            && mouseY >= controlY && mouseY < controlY + CONTROL_HEIGHT) {
                        if (row.kind == Kind.TOGGLE) {
                            row.setBool(!row.boolValue());
                            commit();
                        } else if (row.kind == Kind.CHOICE) {
                            row.stepChoice(1);
                            commit();
                        } else if (mouseX < controlX + STEP_BOX) {
                            row.stepNumber(-1);
                            commit();
                        } else if (mouseX >= controlX + width - STEP_BOX) {
                            row.stepNumber(1);
                            commit();
                        }
                    }
                }
                return true;
            }
        }
        //? if >=1.21.9 {
        /*return super.mouseClicked(new MouseButtonEvent(mouseX, mouseY,
                new MouseButtonInfo(button, 0)), false);
        *///?} else {
        return super.mouseClicked(mouseX, mouseY, button);
        //?}
    }

    @Override
    //? if >=1.20.2 {
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    //?} else {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
    *///?}
        if (insideList(mouseX, mouseY) && scrollY != 0) {
            int next = clampScroll(scroll - (int) Math.signum(scrollY) * SCROLL_STEP);
            if (next != scroll) {
                scroll = next;
                return true;
            }
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

    // ------------------------------------------------------------------ actions

    /** One control changed: persist it, then let the mod re-read what it holds. */
    private void commit() {
        RoadConfig.save();
        RoadConfig.applyRuntimeChanges();
        status = label("screen.howtogo.settings.saved");
        statusUntil = System.currentTimeMillis() + STATUS_MILLIS;
    }

    /**
     * The reset button: first press arms, second press restores.
     *
     * <p>Only the settings this screen shows are restored -- a whole-file reset would also wipe the
     * colour sections, which the file's editor is the right place for. One save for the whole
     * operation rather than one per row: the rows are a single edit as far as the player is
     * concerned, and a run of writes would leave the file at a halfway state if one of them failed.
     */
    private void resetClicked() {
        if (!resetArmed) {
            resetArmed = true;
            status = null;
            buildButtons();
            return;
        }
        for (Row row : rows) {
            row.resetValue.run();
        }
        resetArmed = false;
        buildButtons();
        status = label("screen.howtogo.settings.reset_done");
        statusUntil = System.currentTimeMillis() + STATUS_MILLIS;
        RoadConfig.save();
        RoadConfig.applyRuntimeChanges();
        scroll = clampScroll(scroll);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}
        graphics.fill(0, 0, this.width, this.height, UiTheme.dim());
        // The sheet is rounded like the destination picker's, rather than the square frame the
        // world map uses: this is a window over the game, not a full-screen workspace.
        HudDraw.fillRounded(graphics, panelX, panelY, panelX + panelW, panelY + panelH, 5,
                UiTheme.panel());
        HudDraw.outlineRounded(graphics, panelX, panelY, panelX + panelW, panelY + panelH, 5,
                UiTheme.panelEdge());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        pointerX = mouseX;
        pointerY = mouseY;
        drawFrame(graphics);
        drawList(graphics, mouseX, mouseY);
        if (descriptionVisible) {
            drawDescription(graphics, mouseX, mouseY);
        }
        for (PanelButton button : buttons) {
            button.render(graphics, font, mouseX, mouseY);
        }
    }

    /** The title and the hairlines that separate the three bands of the window. */
    private void drawFrame(GuiGraphics graphics) {
        graphics.drawString(font, this.title, panelX + PAD, panelY + PAD, UiTheme.title(), false);
        graphics.fill(listX, listY - 4, listX + listW, listY - 3, UiTheme.panelEdge());
        if (descriptionVisible) {
            graphics.fill(listX, descY - 4, listX + listW, descY - 3, UiTheme.panelEdge());
        }
    }

    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        Row hovered = rowAt(mouseX, mouseY);
        graphics.enableScissor(listX, listY, listX + listW, listY + listH);
        for (Group group : groups) {
            int headerY = listY + group.contentY - scroll;
            if (headerY + GROUP_HEIGHT > listY && headerY < listY + listH) {
                graphics.drawString(font, group.title,
                        listX + 2, headerY + (GROUP_HEIGHT - 8) / 2, UiTheme.header(), false);
            }
            for (Row row : group.rows) {
                int rowY = listY + row.contentY - scroll;
                if (rowY + ROW_HEIGHT > listY && rowY < listY + listH) {
                    drawRow(graphics, row, rowY, mouseX, mouseY, row == hovered);
                }
            }
        }
        graphics.disableScissor();
        drawScrollbar(graphics);
    }

    private void drawRow(GuiGraphics graphics, Row row, int rowY,
                         int mouseX, int mouseY, boolean hovered) {
        boolean enabled = row.isEnabled();
        if (hovered && enabled) {
            graphics.fill(listX, rowY, listX + listW, rowY + ROW_HEIGHT - 1,
                    UiTheme.rowHoverTint());
        }
        int controlX = controlX(row);
        int controlY = controlY(rowY);
        int controlWidth = controlWidth(row);
        int room = Math.max(30, controlX - (listX + 4) - 6);
        boolean overControl = mouseX >= controlX && mouseX < controlX + controlWidth
                && mouseY >= controlY && mouseY < controlY + CONTROL_HEIGHT;

        // The control's face is a shape, so it is filled before any string of this row is drawn.
        drawControlFace(graphics, row, controlX, controlY, controlWidth, enabled, overControl);

        graphics.drawString(font, PlaceText.ellipsis(font, row.label.getString(), room),
                listX + 4, rowY + 4,
                enabled ? UiTheme.text() : UiTheme.textFaint(), false);
        Component hint = enabled || row.disabledReason == null
                ? row.description : row.disabledReason;
        graphics.drawString(font,
                PlaceText.ellipsis(font, hint.getString(), room),
                listX + 4, rowY + 15,
                enabled ? UiTheme.textMuted() : UiTheme.textFaint(), false);

        drawControlLabel(graphics, row, controlX, controlY, controlWidth, enabled, overControl);
    }

    private void drawControlFace(GuiGraphics graphics, Row row, int x, int y, int width,
                                 boolean enabled, boolean overControl) {
        if (row.kind == Kind.NUMBER) {
            int valueWidth = width - STEP_BOX * 2 - 2;
            drawStepBox(graphics, x, y, enabled && overControl
                    && mouseInBox(x, y));
            drawStepBox(graphics, x + STEP_BOX + 1 + valueWidth + 1, y, enabled && overControl
                    && mouseInBox(x + STEP_BOX + 1 + valueWidth + 1, y));
            return;
        }
        int face;
        if (row.kind == Kind.TOGGLE) {
            // A switched-on toggle still has to answer the cursor: buttonHover is the one face both
            // palettes define as "you are on this", and onButton is its readable label.
            face = !enabled ? UiTheme.button()
                    : row.boolValue() ? (overControl ? UiTheme.buttonHover() : UiTheme.buttonOn())
                    : overControl ? UiTheme.buttonHover() : UiTheme.button();
        } else {
            face = !enabled ? UiTheme.button()
                    : overControl ? UiTheme.selection() : UiTheme.selectionSoft();
        }
        HudDraw.fillRounded(graphics, x, y, x + width, y + CONTROL_HEIGHT, 3, face);
    }

    /**
     * Whether the cursor is over one step box.
     *
     * <p>The face pass runs before the label pass and has no cursor coordinates of its own; both
     * boxes read the frame's pointer position from the two fields above, which keeps the dozens of
     * {@link #drawControlFace} parameters unchanged.
     */
    private boolean mouseInBox(int x, int y) {
        return pointerX >= x && pointerX < x + STEP_BOX
                && pointerY >= y && pointerY < y + CONTROL_HEIGHT;
    }

    private void drawStepBox(GuiGraphics graphics, int x, int y, boolean hovered) {
        HudDraw.fillRounded(graphics, x, y, x + STEP_BOX, y + CONTROL_HEIGHT, 3,
                hovered ? UiTheme.buttonHover() : UiTheme.button());
    }

    private void drawControlLabel(GuiGraphics graphics, Row row, int x, int y, int width,
                                  boolean enabled, boolean hovered) {
        if (row.kind == Kind.NUMBER) {
            String value = row.numberText();
            int valueX = x + STEP_BOX + 2;
            int valueWidth = width - STEP_BOX * 2 - 4;
            graphics.drawString(font, PlaceText.ellipsis(font, value, valueWidth),
                    valueX + Math.max(0, (valueWidth - font.width(value)) / 2),
                    y + (CONTROL_HEIGHT - 8) / 2,
                    enabled ? UiTheme.textStrong() : UiTheme.textFaint(), false);
            int textY = y + (CONTROL_HEIGHT - 8) / 2;
            drawCentred(graphics, "-", x, STEP_BOX, textY,
                    enabled ? UiTheme.onButton() : UiTheme.textFaint());
            drawCentred(graphics, "+", x + width - STEP_BOX, STEP_BOX, textY,
                    enabled ? UiTheme.onButton() : UiTheme.textFaint());
            return;
        }
        if (row.kind == Kind.TOGGLE) {
            int text = !enabled ? UiTheme.textFaint()
                    : row.boolValue() ? UiTheme.onButtonOn() : UiTheme.onButton();
            drawCentred(graphics,
                    Component.translatable(row.boolValue()
                            ? "screen.howtogo.settings.value.on"
                            : "screen.howtogo.settings.value.off").getString(),
                    x, width, y + (CONTROL_HEIGHT - 8) / 2, text);
            return;
        }
        int text = !enabled ? UiTheme.textFaint()
                : hovered ? UiTheme.onSelection() : UiTheme.onSelectionSoft();
        String current = row.choiceLabel().getString();
        graphics.drawString(font, PlaceText.ellipsis(font, current, width - 6),
                x + Math.max(3, (width - font.width(current)) / 2),
                y + (CONTROL_HEIGHT - 8) / 2, text, false);
    }

    private void drawCentred(GuiGraphics graphics, String text, int x, int width, int y, int color) {
        graphics.drawString(font, text, x + Math.max(0, (width - font.width(text)) / 2), y,
                color, false);
    }

    /**
     * The scrollbar: the track under the thumb, drawn only when the content is taller than the
     * window. Wheel-only, like every other list in this mod; a 2 px bar is an indicator here, not a
     * second input surface, which is why no drag state is kept for it.
     */
    private void drawScrollbar(GuiGraphics graphics) {
        if (contentHeight <= listH) {
            return;
        }
        int trackX = listX + listW - 2;
        int thumbHeight = Math.max(16, listH * listH / contentHeight);
        int thumbY = listY + (int) ((listH - thumbHeight)
                * ((double) scroll / Math.max(1, contentHeight - listH)));
        graphics.fill(trackX, listY, trackX + 2, listY + listH, UiTheme.scrollTrack());
        graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, UiTheme.scrollThumb());
    }

    /**
     * The strip under the list: the hovered setting's description, or the reset warning, or the
     * line that says where saves go.
     *
     * <p>A description under the list rather than a tooltip beside the cursor: a tooltip the size of
     * some of these sentences would cover the values it is describing, and the strip is always in
     * the same place, so a reader does not have to chase it.
     */
    private void drawDescription(GuiGraphics graphics, int mouseX, int mouseY) {
        Row hovered = rowAt(mouseX, mouseY);
        Component text;
        int color;
        boolean freshStatus = status != null && System.currentTimeMillis() < statusUntil;
        if (resetArmed) {
            text = label("screen.howtogo.settings.reset_note");
            color = UiTheme.warn();
        } else if (freshStatus) {
            // Before the hovered row: an acknowledgement shown only until the cursor leaves the row
            // that was just edited is an acknowledgement the editor never sees.
            text = status;
            color = UiTheme.okSoft();
        } else if (hovered != null) {
            text = !hovered.isEnabled() && hovered.disabledReason != null
                    ? hovered.disabledReason : hovered.description;
            color = hovered.isEnabled() ? UiTheme.textSecondary() : UiTheme.textFaint();
        } else {
            text = label("screen.howtogo.settings.hint");
            color = UiTheme.textFaint();
        }
        int width = listW - 4;
        List<String> lines = PlaceText.wrap(font, text.getString(), width);
        int y = descY;
        for (int line = 0; line < lines.size() && line < DESC_LINES; line++) {
            String shown = lines.get(line);
            if (line == DESC_LINES - 1 && lines.size() > DESC_LINES) {
                // The strip is a fixed three lines; the rest of a longer sentence is elided rather
                // than silently cut mid-word.
                StringBuilder rest = new StringBuilder(shown);
                for (int next = line + 1; next < lines.size(); next++) {
                    rest.append(' ').append(lines.get(next));
                }
                shown = PlaceText.ellipsis(font, rest.toString(), width);
            }
            graphics.drawString(font, shown, listX + 2, y, color, false);
            y += LINE_HEIGHT;
        }
    }

    // ----------------------------------------------------------------- geometry

    private int controlWidth(Row row) {
        return switch (row.kind) {
            case TOGGLE -> TOGGLE_WIDTH;
            case CHOICE -> Math.max(64, Math.min(150, row.choiceWidth(font) + 16));
            case NUMBER -> STEP_BOX * 2 + 2 + STEP_VALUE_ROOM;
        };
    }

    private int controlX(Row row) {
        return listX + listW - 10 - controlWidth(row);
    }

    private static int controlY(int rowY) {
        return rowY + (ROW_HEIGHT - CONTROL_HEIGHT) / 2;
    }

    private boolean insideList(double mouseX, double mouseY) {
        return mouseX >= listX && mouseX < listX + listW
                && mouseY >= listY && mouseY < listY + listH;
    }

    /** The row under the cursor, in content coordinates so the scroll cannot skew the answer. */
    private Row rowAt(double mouseX, double mouseY) {
        if (!insideList(mouseX, mouseY)) {
            return null;
        }
        int contentY = (int) (mouseY - listY + scroll);
        for (Row row : rows) {
            if (contentY >= row.contentY && contentY < row.contentY + ROW_HEIGHT) {
                return row;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------- rows

    private enum Kind {
        TOGGLE, CHOICE, NUMBER
    }

    private static final class Group {
        private final Component title;
        private final List<Row> rows;
        private int contentY;

        private Group(Component title, List<Row> rows) {
            this.title = title;
            this.rows = rows;
        }
    }

    /**
     * One setting: a name, the sentence explaining it, one control, and the two operations every
     * control needs -- the current value and the way back to the declared default.
     */
    private static final class Row {
        private final Kind kind;
        private final Component label;
        private final Component description;

        private BooleanSupplier boolGetter;
        private Consumer<Boolean> boolSetter;
        private boolean boolDefault;

        private List<Component> choices;
        private IntSupplier choiceGetter;
        private IntConsumer choiceSetter;
        private int choiceDefault;

        private DoubleSupplier numberGetter;
        private DoubleConsumer numberSetter;
        private double min;
        private double max;
        private double step;
        private double numberDefault;
        private boolean wholeNumbers;

        private BooleanSupplier enabledWhen;
        private Component disabledReason;
        private Runnable resetValue;
        private int contentY;

        private Row(Kind kind, Component label, Component description) {
            this.kind = kind;
            this.label = label;
            this.description = description;
        }

        private static Row toggle(String labelKey, String descriptionKey,
                                  BooleanSupplier getter, Consumer<Boolean> setter,
                                  boolean defaultValue) {
            Row row = new Row(Kind.TOGGLE, label(labelKey), label(descriptionKey));
            row.boolGetter = getter;
            row.boolSetter = setter;
            row.boolDefault = defaultValue;
            row.resetValue = () -> setter.accept(defaultValue);
            return row;
        }

        private static Row choice(String labelKey, String descriptionKey,
                                  List<Component> choices, IntSupplier getter,
                                  IntConsumer setter, int defaultIndex) {
            Row row = new Row(Kind.CHOICE, label(labelKey), label(descriptionKey));
            row.choices = choices;
            row.choiceGetter = getter;
            row.choiceSetter = setter;
            row.choiceDefault = Math.max(0, defaultIndex);
            row.resetValue = () -> setter.accept(row.choiceDefault);
            return row;
        }

        private static Row number(String labelKey, String descriptionKey,
                                  DoubleSupplier getter, DoubleConsumer setter,
                                  double min, double max, double step,
                                  double defaultValue, boolean wholeNumbers) {
            Row row = new Row(Kind.NUMBER, label(labelKey), label(descriptionKey));
            row.numberGetter = getter;
            row.numberSetter = setter;
            row.min = min;
            row.max = max;
            row.step = step;
            row.numberDefault = defaultValue;
            row.wholeNumbers = wholeNumbers;
            row.resetValue = () -> setter.accept(defaultValue);
            return row;
        }

        private Row onlyWhen(BooleanSupplier condition, Component reason) {
            this.enabledWhen = condition;
            this.disabledReason = reason;
            return this;
        }

        private boolean isEnabled() {
            return enabledWhen == null || enabledWhen.getAsBoolean();
        }

        private boolean boolValue() {
            return boolGetter.getAsBoolean();
        }

        private void setBool(boolean value) {
            boolSetter.accept(value);
        }

        private Component choiceLabel() {
            int index = choiceGetter.getAsInt();
            return index >= 0 && index < choices.size() ? choices.get(index) : Component.empty();
        }

        private void stepChoice(int direction) {
            int size = choices.size();
            if (size == 0) {
                return;
            }
            int index = Math.floorMod(choiceGetter.getAsInt() + direction, size);
            choiceSetter.accept(index);
        }

        private int choiceWidth(Font font) {
            int width = 0;
            for (Component choice : choices) {
                width = Math.max(width, font.width(choice.getString()));
            }
            return width;
        }

        private void stepNumber(int direction) {
            double value = numberGetter.getAsDouble();
            double next = Math.max(min, Math.min(max, value + direction * step));
            numberSetter.accept(next);
        }

        private String numberText() {
            double value = numberGetter.getAsDouble();
            if (wholeNumbers || value == Math.rint(value)) {
                return Long.toString(Math.round(value));
            }
            return String.format(Locale.ROOT, "%.1f", value);
        }
    }
}
