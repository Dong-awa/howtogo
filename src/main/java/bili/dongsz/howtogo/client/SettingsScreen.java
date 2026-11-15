package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.store.RoutePreferenceStore;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
// 1.21.9 bundled the mouse coordinates and the button into a record.
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
 * The mod's in-game settings: the player-facing half of the client config, editable without a file.
 * Carries only the settings players reach for -- interface look, where navigation is drawn, trip
 * fallbacks, optional map layers; the rest stay in the config file (see the ROWS list for the line).
 * Controls are this mod's own, drawn in UiTheme colours like every other screen: a toggle, a chip, a
 * stepper, a page-opening button, PanelButtons in the footer. No sliders: steps suit coordinates
 * better than drags.
 * Every edit is persisted immediately through RoadConfig's typed setters via commit(); there is no
 * apply button and no save on close.
 * All colours and motion come from UiTheme and UiMotion, so a theme change recolours the open
 * screen.
 * scrollShown is the drawn offset, and the hit test reads it too, so a click during a scroll lands
 * on the row under the cursor.
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
    /**
     * How wide the action row's control is. Fixed rather than measured: a longer translated label is
     * elided instead of pushing the control off the panel.
     */
    private static final int ACTION_WIDTH = 62;
    private static final int STEP_BOX = 16;
    private static final int STEP_VALUE_ROOM = 52;
    private static final int DESC_LINES = 3;
    private static final int SCROLL_STEP = 18;
    private static final long STATUS_MILLIS = 2500L;

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

    /**
     * Where the list is drawn, as opposed to where scroll says it is heading; both drawing and hit test
     * read this value, so a click during a scroll lands on the visible row. See UiMotion.follow.
     */
    private float scrollShown;

    private final UiMotion.Slides rowHovers = new UiMotion.Slides();

    /**
     * The stepper buttons' hover values, in a pool of their own: keyed by row and side, so one row's box
     * cannot drive another's.
     */
    private final UiMotion.Slides boxHovers = new UiMotion.Slides();

    private float open = 1.0F;

    /**
     * The opacity of the dimming and sheet this frame. Same as open except during a page hand-off, where
     * the surface stays full while the content cross-fades; see UiMotion.ScreenState.surface.
     */
    private float surface = 1.0F;

    private boolean resetArmed;
    private Component status;
    private long statusUntil;
    private boolean descriptionVisible;
    private double pointerX;
    private double pointerY;

    SettingsScreen(Screen parent) {
        super(Component.translatable("screen.howtogo.settings"));
        this.parent = parent;
        buildRows();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }


    /** The settings this screen exposes, in the order they are read and reset. */
    private void buildRows() {
        List<UiTheme.Mode> themes = List.of(UiTheme.Mode.values());
        List<MapDisplayMode> displayModes = List.of(MapDisplayMode.values());
        List<RoadLabelStyle> labelStyles = List.of(RoadLabelStyle.values());
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
                Row.toggle("howtogo.configuration.ui_animations",
                        "screen.howtogo.settings.ui_animations.desc",
                        RoadConfig::uiAnimations,
                        RoadConfig::setUiAnimations, true),
                // The third question about how a road reads on the map: name, classification sign, or both in the
                // same card.
                Row.choice("howtogo.configuration.road_label_style",
                        "screen.howtogo.settings.road_label_style.desc",
                        List.of(label("howtogo.configuration.road_label_style.name"),
                                label("howtogo.configuration.road_label_style.sign"),
                                label("howtogo.configuration.road_label_style.mixed")),
                        () -> labelStyles.indexOf(RoadConfig.roadLabelStyle()),
                        index -> RoadConfig.setRoadLabelStyle(labelStyles.get(index)),
                        RoadLabelStyle.defaultStyle().ordinal()),
                // The sign's colour and word are per category, so this row is a door: the page behind it edits the
                // player's categories.
                Row.action("howtogo.configuration.road_categories",
                        "screen.howtogo.settings.road_categories.desc",
                        () -> Screens.set(new RoadCategoryScreen(this))));

        // The compact HUD card exists only while the map half is drawn on Xaero's Minimap, so its rows follow
        // the effective mode: choosing Xaero on an install that cannot provide it falls back to the HUD panel.
        BooleanSupplier xaeroMode = () -> NavMapMode.effective() == MapDisplayMode.XAERO_MINIMAP;
        Component needsXaero = label("screen.howtogo.settings.needs_xaero");
        group("screen.howtogo.settings.group.hud",
                // First, because it decides what the group is about: the mod's own panel or Xaero's Minimap; the
                // compact-card rows exist only for the second.
                Row.choice("howtogo.configuration.map_display_mode",
                        "screen.howtogo.settings.map_display_mode.desc",
                        List.of(label("howtogo.configuration.map_display_mode.hud"),
                                label("howtogo.configuration.map_display_mode.xaero_minimap")),
                        () -> displayModes.indexOf(RoadConfig.mapDisplayMode()),
                        index -> RoadConfig.setMapDisplayMode(displayModes.get(index)),
                        displayModes.indexOf(MapDisplayMode.defaultMode())),
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
                Row.toggle("howtogo.configuration.transit_mixed",
                        "screen.howtogo.settings.transit_mixed.desc",
                        RoadConfig::transitMixed,
                        RoadConfig::setTransitMixed, true),
                Row.number("howtogo.configuration.transit_wait_seconds",
                        "screen.howtogo.settings.transit_wait_seconds.desc",
                        RoadConfig::transitWaitSeconds,
                        RoadConfig::setTransitWaitSeconds,
                        0, 3600, 10, 60, true),
                Row.number("howtogo.configuration.walk_offroad_blocks",
                        "screen.howtogo.settings.walk_offroad_blocks.desc",
                        RoadConfig::walkOffroadBlocks,
                        RoadConfig::setWalkOffroadBlocks,
                        0, 256, 8, 32, true),
                Row.number("howtogo.configuration.station_snap_blocks",
                        "screen.howtogo.settings.station_snap_blocks.desc",
                        RoadConfig::stationSnapBlocks,
                        RoadConfig::setStationSnapBlocks,
                        1, 256, 4, 32, true),
                // The only row here that does not write the config: the guidance reads it from the preference store,
                // so writing the config would be a visible no-op on installs with a saved preference file.
                Row.toggle("howtogo.configuration.straight_announcements",
                        "screen.howtogo.settings.straight_announcements.desc",
                        RoutePreferenceStore::straightAnnouncements,
                        RoutePreferenceStore::setStraightAnnouncements, true));

        // MTR's whole-map reader is consulted only while MTR data is read; the row stays visible and greyed,
        // since the switch above explains it.
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

        group("screen.howtogo.settings.group.log",
                Row.toggle("howtogo.configuration.log_info",
                        "screen.howtogo.settings.log_info.desc",
                        RoadConfig::logInfo,
                        RoadConfig::setLogInfo, true));
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
        // A window shorter than three bands cannot hold the strip; the list keeps its room and the strip is
        // omitted.
        descriptionVisible = descY - GAP - listY >= ROW_HEIGHT;

        measureContent();
        scroll = clampScroll(scroll);
        // A resize re-runs init(), so the drawn offset starts at the target rather than gliding in from the
        // old geometry.
        scrollShown = scroll;
        buildButtons();
    }

    /** Puts every row's top edge into content coordinates: the same numbers draw and hit-test. */
    private void measureContent() {
        int y = 0;
        int index = 0;
        for (Group group : groups) {
            group.contentY = y;
            y += GROUP_HEIGHT;
            for (Row row : group.rows) {
                row.contentY = y;
                // Position in the flat list, which is what the stagger and hover pool are keyed by.
                row.index = index++;
                y += ROW_HEIGHT;
            }
            y += GAP;
        }
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
            for (PanelButton candidate : buttons) {
                if (candidate.click(mouseX, mouseY)) {
                    return true;
                }
            }
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
                    int rowY = listY + row.contentY - Math.round(scrollShown);
                    int controlX = controlX(row);
                    int controlY = controlY(rowY);
                    int width = controlWidth(row);
                    // The hit test is the control's own rectangle, not the whole row: label and description are text.
                    // Same rule as DestinationScreen's option rows.
                    if (mouseX >= controlX && mouseX < controlX + width
                            && mouseY >= controlY && mouseY < controlY + CONTROL_HEIGHT) {
                        if (row.kind == Kind.TOGGLE) {
                            row.setBool(!row.boolValue());
                            commit();
                        } else if (row.kind == Kind.CHOICE) {
                            row.stepChoice(1);
                            commit();
                        } else if (row.kind == Kind.BUTTON) {
                            // A door, not a value: nothing here changed, so there is nothing to commit.
                            row.onAction.run();
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


    /** One control changed: persist it, then let the mod re-read what it holds. */
    private void commit() {
        RoadConfig.save();
        RoadConfig.applyRuntimeChanges();
        status = label("screen.howtogo.settings.saved");
        statusUntil = System.currentTimeMillis() + STATUS_MILLIS;
    }

    /**
     * First press arms, second restores. Only the settings this screen shows are restored, and one save
     * covers the whole operation, so a failure cannot leave the file halfway.
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


    @Override
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    /*public void renderBackground(GuiGraphics graphics) {
    *///?}
        graphics.fill(-UiMotion.SCRIM_MARGIN, -UiMotion.SCRIM_MARGIN,
                this.width + UiMotion.SCRIM_MARGIN, this.height + UiMotion.SCRIM_MARGIN,
                UiMotion.fade(UiTheme.dim(), surface));
        // The sheet is rounded like the destination picker's, not the square world-map frame: this is a
        // window over the game.
        HudDraw.fillRounded(graphics, panelX, panelY, panelX + panelW, panelY + panelH, 5,
                UiMotion.fade(UiTheme.panel(), surface));
        HudDraw.outlineRounded(graphics, panelX, panelY, panelX + panelW, panelY + panelH, 5,
                UiMotion.fade(UiTheme.panelEdge(), surface));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        UiMotion.ScreenState motion = UiMotion.of(this);
        open = motion.opacity();
        surface = motion.surface();
        // The whole frame is displaced, not only faded: a window appearing in place reads as a cut. Pushed
        // before any pixel, so dimming, sheet, rows and footer travel as one surface. The game rebuilds the
        // pose each frame, so the pop at the end is the whole bookkeeping.
        UiMotion.pushFrame(graphics, 0.0F, motion.offsetY(UiMotion.RISE, UiMotion.FALL));
        // 1.20.2 started drawing a screen's background from Screen.render; 1.20.1 has no such call, so it is
        // asked for here.
        //? if <1.20.2 {
        /*renderBackground(graphics);
        *///?}
        super.render(graphics, mouseX, mouseY, partialTick);
        pointerX = mouseX;
        pointerY = mouseY;
        scrollShown = UiMotion.follow(scrollShown, scroll, UiMotion.FOLLOW_SECONDS);
        drawFrame(graphics);
        drawList(graphics, mouseX, mouseY);
        if (descriptionVisible) {
            drawDescription(graphics, mouseX, mouseY);
        }
        for (PanelButton button : buttons) {
            button.render(graphics, font, mouseX, mouseY);
        }
        // Nothing is drawn after this, which keeps the push/pop pair balanced.
        UiMotion.popFrame(graphics);
    }

    /** The title and the hairlines that separate the three bands of the window. */
    private void drawFrame(GuiGraphics graphics) {
        graphics.drawString(font, this.title, panelX + PAD, panelY + PAD,
                UiMotion.fade(UiTheme.title(), open), false);
        graphics.fill(listX, listY - 4, listX + listW, listY - 3,
                UiMotion.fade(UiTheme.panelEdge(), open));
        if (descriptionVisible) {
            graphics.fill(listX, descY - 4, listX + listW, descY - 3,
                    UiMotion.fade(UiTheme.panelEdge(), open));
        }
    }

    private void drawList(GuiGraphics graphics, int mouseX, int mouseY) {
        Row hovered = rowAt(mouseX, mouseY);
        int scrollPixels = Math.round(scrollShown);
        UiMotion.clip(graphics, listX, listY, listX + listW, listY + listH);
        for (Group group : groups) {
            int headerY = listY + group.contentY - scrollPixels;
            if (headerY + GROUP_HEIGHT > listY && headerY < listY + listH) {
                graphics.drawString(font, group.title,
                        listX + 2, headerY + (GROUP_HEIGHT - 8) / 2,
                        UiMotion.fade(UiTheme.header(), open), false);
            }
            for (Row row : group.rows) {
                int rowY = listY + row.contentY - scrollPixels;
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
        // How far the row has arrived in the list's entrance and how far the cursor has moved onto it,
        // multiplied: a row the cursor is already on when the window opens still has to arrive.
        float arrived = UiMotion.stagger(row.index, open);
        float lit = rowHovers.get(row.index).to(hovered && enabled ? 1.0F : 0.0F).eased();
        if (lit > 0.001F) {
            graphics.fill(listX, rowY, listX + listW, rowY + ROW_HEIGHT - 1,
                    UiMotion.fade(UiTheme.rowHoverTint(), lit * arrived));
        }
        int controlX = controlX(row);
        int controlY = controlY(rowY);
        int controlWidth = controlWidth(row);
        int room = Math.max(30, controlX - (listX + 4) - 6);
        boolean overControl = mouseX >= controlX && mouseX < controlX + controlWidth
                && mouseY >= controlY && mouseY < controlY + CONTROL_HEIGHT;

        // The control's face is a shape, so it is filled before any string of this row is drawn.
        drawControlFace(graphics, row, controlX, controlY, controlWidth, enabled, overControl,
                lit, arrived);

        graphics.drawString(font, PlaceText.ellipsis(font, row.label.getString(), room),
                listX + 4, rowY + 4,
                UiMotion.fade(enabled ? UiTheme.text() : UiTheme.textFaint(), arrived), false);
        Component hint = enabled || row.disabledReason == null
                ? row.description : row.disabledReason;
        graphics.drawString(font,
                PlaceText.ellipsis(font, hint.getString(), room),
                listX + 4, rowY + 15,
                UiMotion.fade(enabled ? UiTheme.textMuted() : UiTheme.textFaint(), arrived), false);

        drawControlLabel(graphics, row, controlX, controlY, controlWidth, enabled, lit, arrived);
    }

    /**
     * The face of a row's control, mixed from its resting face to its lit one by the hover value, so the
     * states between are seen rather than switched.
     */
    private void drawControlFace(GuiGraphics graphics, Row row, int x, int y, int width,
                                 boolean enabled, boolean overControl, float lit, float arrived) {
        if (row.kind == Kind.NUMBER) {
            int valueWidth = width - STEP_BOX * 2 - 2;
            drawStepBox(graphics, row, 0, x, y, enabled && overControl
                    && mouseInBox(x, y), arrived);
            drawStepBox(graphics, row, 1, x + STEP_BOX + 1 + valueWidth + 1, y,
                    enabled && overControl
                            && mouseInBox(x + STEP_BOX + 1 + valueWidth + 1, y), arrived);
            return;
        }
        int face;
        if (row.kind == Kind.TOGGLE) {
            // A switched-on toggle still answers the cursor: buttonHover means "you are on this", and onButton
            // is its readable label.
            int resting = row.boolValue() ? UiTheme.buttonOn() : UiTheme.button();
            face = !enabled ? UiTheme.button()
                    : UiMotion.mix(resting, UiTheme.buttonHover(), overControl ? lit : 0.0F);
        } else if (row.kind == Kind.BUTTON) {
            face = !enabled ? UiTheme.button()
                    : UiMotion.mix(UiTheme.button(), UiTheme.buttonHover(),
                            overControl ? lit : 0.0F);
        } else {
            face = !enabled ? UiTheme.button()
                    : UiMotion.mix(UiTheme.selectionSoft(), UiTheme.selection(),
                            overControl ? lit : 0.0F);
        }
        HudDraw.fillRounded(graphics, x, y, x + width, y + CONTROL_HEIGHT, 3,
                UiMotion.fade(face, arrived));
    }

    /**
     * Whether the cursor is over one step box. The face pass has no cursor coordinates of its own, so it
     * reads the frame's pointer fields, which keeps drawControlFace's parameters unchanged.
     */
    private boolean mouseInBox(int x, int y) {
        return pointerX >= x && pointerX < x + STEP_BOX
                && pointerY >= y && pointerY < y + CONTROL_HEIGHT;
    }

    private void drawStepBox(GuiGraphics graphics, Row row, int box, int x, int y,
                             boolean hovered, float arrived) {
        float lit = boxHovers.get(row.index * 2 + box).to(hovered ? 1.0F : 0.0F).eased();
        HudDraw.fillRounded(graphics, x, y, x + STEP_BOX, y + CONTROL_HEIGHT, 3,
                UiMotion.fade(UiMotion.mix(UiTheme.button(), UiTheme.buttonHover(), lit), arrived));
    }

    private void drawControlLabel(GuiGraphics graphics, Row row, int x, int y, int width,
                                  boolean enabled, float lit, float arrived) {
        if (row.kind == Kind.NUMBER) {
            String value = row.numberText();
            int valueX = x + STEP_BOX + 2;
            int valueWidth = width - STEP_BOX * 2 - 4;
            graphics.drawString(font, PlaceText.ellipsis(font, value, valueWidth),
                    valueX + Math.max(0, (valueWidth - font.width(value)) / 2),
                    y + (CONTROL_HEIGHT - 8) / 2,
                    UiMotion.fade(enabled ? UiTheme.textStrong() : UiTheme.textFaint(), arrived),
                    false);
            int textY = y + (CONTROL_HEIGHT - 8) / 2;
            drawCentred(graphics, "-", x, STEP_BOX, textY,
                    UiMotion.fade(enabled ? UiTheme.onButton() : UiTheme.textFaint(), arrived));
            drawCentred(graphics, "+", x + width - STEP_BOX, STEP_BOX, textY,
                    UiMotion.fade(enabled ? UiTheme.onButton() : UiTheme.textFaint(), arrived));
            return;
        }
        if (row.kind == Kind.BUTTON) {
            // The face is a button's, so the label is too: centred, in that face's text colour, and elided rather
            // than wrapped.
            drawCentred(graphics, PlaceText.ellipsis(font,
                            Component.translatable("howtogo.configuration.open").getString(),
                            width - 6),
                    x, width, y + (CONTROL_HEIGHT - 8) / 2,
                    UiMotion.fade(enabled ? UiTheme.onButton() : UiTheme.textFaint(), arrived));
            return;
        }
        if (row.kind == Kind.TOGGLE) {
            int text = !enabled ? UiTheme.textFaint()
                    : row.boolValue() ? UiTheme.onButtonOn() : UiTheme.onButton();
            drawCentred(graphics,
                    Component.translatable(row.boolValue()
                            ? "screen.howtogo.settings.value.on"
                            : "screen.howtogo.settings.value.off").getString(),
                    x, width, y + (CONTROL_HEIGHT - 8) / 2, UiMotion.fade(text, arrived));
            return;
        }
        int text = !enabled ? UiTheme.textFaint()
                : UiMotion.mix(UiTheme.onSelectionSoft(), UiTheme.onSelection(), lit);
        String current = row.choiceLabel().getString();
        graphics.drawString(font, PlaceText.ellipsis(font, current, width - 6),
                x + Math.max(3, (width - font.width(current)) / 2),
                y + (CONTROL_HEIGHT - 8) / 2, UiMotion.fade(text, arrived), false);
    }

    private void drawCentred(GuiGraphics graphics, String text, int x, int width, int y, int color) {
        graphics.drawString(font, text, x + Math.max(0, (width - font.width(text)) / 2), y,
                color, false);
    }

    private void drawScrollbar(GuiGraphics graphics) {
        if (contentHeight <= listH) {
            return;
        }
        int trackX = listX + listW - 2;
        int thumbHeight = Math.max(16, listH * listH / contentHeight);
        int thumbY = listY + (int) ((listH - thumbHeight)
                * ((double) scrollShown / Math.max(1, contentHeight - listH)));
        graphics.fill(trackX, listY, trackX + 2, listY + listH,
                UiMotion.fade(UiTheme.scrollTrack(), open));
        graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight,
                UiMotion.fade(UiTheme.scrollThumb(), open));
    }

    /**
     * The strip under the list: the hovered setting's description, the reset warning, or where saves go.
     * A fixed strip rather than a tooltip, which would cover the values it describes.
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
            // Before the hovered row: an acknowledgement the editor never sees is no acknowledgement.
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
                // The strip is a fixed three lines; longer text is elided rather than cut mid-word.
                StringBuilder rest = new StringBuilder(shown);
                for (int next = line + 1; next < lines.size(); next++) {
                    rest.append(' ').append(lines.get(next));
                }
                shown = PlaceText.ellipsis(font, rest.toString(), width);
            }
            graphics.drawString(font, shown, listX + 2, y, UiMotion.fade(color, open), false);
            y += LINE_HEIGHT;
        }
    }


    private int controlWidth(Row row) {
        return switch (row.kind) {
            case TOGGLE -> TOGGLE_WIDTH;
            case CHOICE -> Math.max(64, Math.min(150, row.choiceWidth(font) + 16));
            case NUMBER -> STEP_BOX * 2 + 2 + STEP_VALUE_ROOM;
            case BUTTON -> ACTION_WIDTH;
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

    private Row rowAt(double mouseX, double mouseY) {
        if (!insideList(mouseX, mouseY)) {
            return null;
        }
        // The drawn offset, not the target: while a scroll is travelling, the row under the cursor is the one
        // drawn there, not the one the list is heading for.
        int contentY = (int) (mouseY - listY + Math.round(scrollShown));
        for (Row row : rows) {
            if (contentY >= row.contentY && contentY < row.contentY + ROW_HEIGHT) {
                return row;
            }
        }
        return null;
    }


    private enum Kind {
        /** A switch: one boolean, toggled in place. */
        TOGGLE,
        CHOICE,
        NUMBER,
        /** A control that opens something else and holds no value of its own. */
        BUTTON
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
     * One setting: a name, the sentence explaining it, one control, and the operations every control
     * needs -- current value and way back to the default.
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
        /** The action a BUTTON row runs; null on every other kind. */
        private Runnable onAction;
        private Runnable resetValue;
        private int contentY;
        /** The row's place in the flat list, which its hover slide and stagger are keyed by. */
        private int index;

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

        private static Row action(String labelKey, String descriptionKey, Runnable action) {
            Row row = new Row(Kind.BUTTON, label(labelKey), label(descriptionKey));
            row.onAction = action;
            row.resetValue = () -> { };
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
