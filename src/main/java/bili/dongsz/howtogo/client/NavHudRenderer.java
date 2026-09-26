package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.Route;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import static bili.dongsz.howtogo.client.HudDraw.*;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game navigation overlay: a corner panel with a mini-map and the trip readout under it.
 *
 * <p>Shown whenever a destination is set and no screen is open, so navigation keeps working after
 * the fullscreen map is closed -- the fullscreen map already carries its own readout.
 *
 * <p>The mini-map is drawn from this mod's own road data rather than borrowing Xaero's rendering:
 * the Minimap is an optional dependency and may not be installed at all, so there would otherwise
 * be nothing to draw into.
 */
public final class NavHudRenderer {

    // ------------------------------------------------------------------ layout

    private static final int MARGIN = 6;
    private static final int PADDING = 6;
    private static final int HEADER_HEIGHT = 12;
    /** Side of the rotating mini-map. */
    private static final int MAP_SIZE = 120;
    /** The panel is exactly as wide as the map it holds, plus the padding either side of it. */
    private static final int PANEL_WIDTH = MAP_SIZE + PADDING * 2;
    /** Left gutter of the readout, left clear for the turn arrow. */
    private static final int READOUT_TEXT_X = 26;
    /**
     * Height of the readout block when nothing extra is being said: the rows down to the journey
     * line, with room under them for the fallback hint.
     */
    private static final int READOUT_HEIGHT = 62;
    /**
     * Height of the readout block while the narrator hint is up, which needs the two rows below the
     * fallback one. Only then does the panel reach this far down.
     */
    private static final int READOUT_HINT_HEIGHT = 84;
    /** Top of the narrator hint line inside the readout block. */
    private static final int READOUT_HINT_Y = 61;
    /** The narrator hint is wrapped, and two lines is all the block has room for. */
    private static final int NARRATOR_HINT_LINES = 2;
    private static final int NARRATOR_HINT_LINE_HEIGHT = 11;
    private static final int CORNER_RADIUS = 4;

    /** Half the world span the mini-map covers, in blocks. */
    private static final double VIEW_RADIUS = 170.0;

    /** Height of one line of the panel's font, for the label boxes. */
    private static final int LABEL_HEIGHT = 9;

    // ------------------------------------------------------------------ palette

    private static final int COLOR_PANEL = 0xE812171C;
    private static final int COLOR_PANEL_EDGE = 0xFF2C3540;
    private static final int COLOR_MAP_BACKDROP = 0xFF0B0F13;
    private static final int COLOR_ACCENT = 0xFF2FD0FF;
    private static final int COLOR_HEADER_TEXT = 0xFF8FA0B0;
    private static final int COLOR_PRIMARY_TEXT = 0xFFF2F6FA;
    private static final int COLOR_SECONDARY_TEXT = 0xFFA8B4C0;
    private static final int COLOR_WARN = 0xFFFFB454;
    private static final int COLOR_OK = 0xFF6BE58A;
    private static final int COLOR_TRACK = 0xFF2C3540;

    private static final int COLOR_ROUTE = 0xFF2FD0FF;
    private static final int COLOR_ROUTE_DONE = 0xFF6E6E6E;
    private static final int COLOR_ORIGIN = 0xFF44FF88;
    private static final int COLOR_DESTINATION = 0xFFFF5555;
    private static final int COLOR_PLAYER = 0xFFFFFFFF;
    /** Road names on the mini-map: quieter than the readout, since the map is the backdrop. */
    private static final int COLOR_MAP_LABEL = 0xFFDCE3EA;

    private static final double ROAD_THICKNESS = 1.0;
    private static final double ROUTE_THICKNESS = 2.6;

    private NavHudRenderer() {
    }

    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || mc.level == null) {
            return;
        }
        if (Navigation.target() == null) {
            return;
        }
        draw(event.getGuiGraphics(), mc);
    }

    // -------------------------------------------------------------------- panel

    private static void draw(GuiGraphics graphics, Minecraft mc) {
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int panelHeight = panelHeight();
        // Floored at MARGIN so a very narrow window cannot push the panel off the left edge.
        int panelX = Math.max(MARGIN, screenWidth - PANEL_WIDTH - MARGIN);
        int panelY = MARGIN;

        // Panel: a dark card with a soft edge, drawn as rounded spans so the corners do not look
        // like a raw rectangle.
        fillRounded(graphics, panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight,
                CORNER_RADIUS, COLOR_PANEL);
        outlineRounded(graphics, panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight,
                CORNER_RADIUS, COLOR_PANEL_EDGE);

        int contentX = panelX + PADDING;
        int headerY = panelY + PADDING;
        drawHeader(graphics, mc, contentX, headerY);

        int mapX = contentX;
        int mapY = headerY + HEADER_HEIGHT;
        graphics.fill(mapX - 1, mapY - 1, mapX + MAP_SIZE + 1, mapY + MAP_SIZE + 1, COLOR_PANEL_EDGE);
        graphics.fill(mapX, mapY, mapX + MAP_SIZE, mapY + MAP_SIZE, COLOR_MAP_BACKDROP);
        drawMiniMap(graphics, mc, mapX, mapY);

        drawInfo(graphics, mc, contentX, mapY + MAP_SIZE + PADDING);
    }

    /**
     * Height of the whole panel.
     *
     * <p>The map and the header are fixed; the readout under them is as tall as what it actually
     * holds. Only the narrator hint's two rows are ever in doubt -- they are up when the speech
     * engine failed and down the rest of the time -- so the panel is the shape it has always been
     * unless that hint is being shown, rather than carrying two empty rows under the journey line
     * for the whole session.
     */
    private static int panelHeight() {
        int readout = Narration.unavailable() ? READOUT_HINT_HEIGHT : READOUT_HEIGHT;
        return PADDING * 2 + HEADER_HEIGHT + MAP_SIZE + readout;
    }

    private static void drawHeader(GuiGraphics graphics, Minecraft mc, int x, int y) {
        Destination destination = Navigation.target();
        if (destination == null) {
            return;
        }
        // Always the destination. Leading with the road the player is standing on reads as the
        // panel reporting a position the player never asked about, and the road being entered is
        // already named on the instruction line below, so nothing is lost by dropping it here.
        String label = Component.translatable("hud.howtogo.hud_destination",
                destination.name()).getString();
        // The mode shares this row rather than the readout below: the readout is already tight on
        // width at this panel size, and the mode belongs next to the heading, not buried at the end
        // of a sentence that may not fit.
        String mode = Navigation.modeLabel();
        int modeWidth = mc.font.width(mode);
        graphics.drawString(mc.font, mc.font.plainSubstrByWidth(label, MAP_SIZE - modeWidth - 6),
                x, y, COLOR_HEADER_TEXT, false);
        graphics.drawString(mc.font, mode, x + MAP_SIZE - modeWidth, y, COLOR_ACCENT, false);
    }

    // ------------------------------------------------------------------ mini-map

    private static void drawMiniMap(GuiGraphics graphics, Minecraft mc, int mapX, int mapY) {
        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();
        double scale = MAP_SIZE / (VIEW_RADIUS * 2.0);
        double centerX = mapX + MAP_SIZE / 2.0;
        double centerY = mapY + MAP_SIZE / 2.0;

        // Rotate so the direction the player faces points up, the way a navigation app does.
        // Minecraft yaw 0 faces south (+Z) with forward (-sin y, cos y); rotating world deltas by
        // (PI - yaw) sends that forward vector to screen (0, -1), i.e. straight up.
        double theta = Math.PI - Math.toRadians(mc.player.getYRot());
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);

        double minX = mapX;
        double minY = mapY;
        double maxX = mapX + MAP_SIZE;
        double maxY = mapY + MAP_SIZE;

        PoseStack pose = graphics.pose();
        pose.pushPose();
        VertexConsumer vc = HudDraw.consumer(graphics);
        PoseStack.Pose last = pose.last();

        // The map is rotated to the player's heading, so a corner of it is sqrt(2) times its
        // half-width away in world blocks. The guard has to cover the corners: at the old bound of
        // half-width plus forty -- which is *less* than the corner distance -- a road visible in a
        // corner of the panel was thrown away for being too far from the player.
        double reach = VIEW_RADIUS * Math.sqrt(2.0) + 8;
        RoadNetwork network = RoadStore.get();
        for (RoadSegment segment : network.segmentsSnapshot()) {
            for (int i = 1; i < segment.vertexCount(); i++) {
                double ax = segment.x(i - 1);
                double az = segment.z(i - 1);
                double bx = segment.x(i);
                double bz = segment.z(i);
                if (outsideReach(ax, az, bx, bz, playerX, playerZ, reach)) {
                    continue;
                }
                int color = 0xFF000000 | (segment.roadClass().color() & 0xFFFFFF);
                emitClipped(last, vc,
                        screenX(ax - playerX, az - playerZ, cos, sin, scale, centerX),
                        screenY(ax - playerX, az - playerZ, cos, sin, scale, centerY),
                        screenX(bx - playerX, bz - playerZ, cos, sin, scale, centerX),
                        screenY(bx - playerX, bz - playerZ, cos, sin, scale, centerY),
                        ROAD_THICKNESS * 0.5, color, 0x9A, minX, minY, maxX, maxY);
            }
        }

        // Create's tracks, drawn in with the roads and culled by the same reach: the map is a view of
        // the ground, and a rail the player can see on the world map belongs on this one too. The
        // layer is bounded by its own scan radius, so this pass is a handful of polylines.
        for (RoadSegment segment : RailTrackStore.segments()) {
            for (int i = 1; i < segment.vertexCount(); i++) {
                double ax = segment.x(i - 1);
                double az = segment.z(i - 1);
                double bx = segment.x(i);
                double bz = segment.z(i);
                if (outsideReach(ax, az, bx, bz, playerX, playerZ, reach)) {
                    continue;
                }
                int color = 0xFF000000 | (segment.roadClass().color() & 0xFFFFFF);
                emitClipped(last, vc,
                        screenX(ax - playerX, az - playerZ, cos, sin, scale, centerX),
                        screenY(ax - playerX, az - playerZ, cos, sin, scale, centerY),
                        screenX(bx - playerX, bz - playerZ, cos, sin, scale, centerX),
                        screenY(bx - playerX, bz - playerZ, cos, sin, scale, centerY),
                        ROAD_THICKNESS * 0.5, color, 0x9A, minX, minY, maxX, maxY);
            }
        }

        Route route = Navigation.route();
        if (route.isPresent()) {
            List<double[]> points = route.points();
            double travelled = Navigation.travelled();
            double cumulative = 0;
            for (int i = 1; i < points.size(); i++) {
                double[] a = points.get(i - 1);
                double[] b = points.get(i);
                double segmentLength = Math.hypot(b[0] - a[0], b[1] - a[1]);
                if (segmentLength < 1.0E-9) {
                    continue;
                }
                double ax = screenX(a[0] - playerX, a[1] - playerZ, cos, sin, scale, centerX);
                double ay = screenY(a[0] - playerX, a[1] - playerZ, cos, sin, scale, centerY);
                double bx = screenX(b[0] - playerX, b[1] - playerZ, cos, sin, scale, centerX);
                double by = screenY(b[0] - playerX, b[1] - playerZ, cos, sin, scale, centerY);
                double half = ROUTE_THICKNESS * 0.5;

                if (cumulative + segmentLength <= travelled) {
                    emitClipped(last, vc, ax, ay, bx, by, half, COLOR_ROUTE_DONE, 0xFF,
                            minX, minY, maxX, maxY);
                } else if (cumulative >= travelled) {
                    emitClipped(last, vc, ax, ay, bx, by, half, COLOR_ROUTE, 0xFF,
                            minX, minY, maxX, maxY);
                } else {
                    // Split exactly where the player is, so the grey edge creeps along with them
                    // instead of snapping forward one whole segment at a time.
                    double t = (travelled - cumulative) / segmentLength;
                    double mx = ax + (bx - ax) * t;
                    double my = ay + (by - ay) * t;
                    emitClipped(last, vc, ax, ay, mx, my, half, COLOR_ROUTE_DONE, 0xFF,
                            minX, minY, maxX, maxY);
                    emitClipped(last, vc, mx, my, bx, by, half, COLOR_ROUTE, 0xFF,
                            minX, minY, maxX, maxY);
                }
                cumulative += segmentLength;
            }
        }

        double originX = Navigation.tripOriginX();
        double originZ = Navigation.tripOriginZ();
        if (!Double.isNaN(originX)) {
            emitMarker(last, vc, screenX(originX - playerX, originZ - playerZ, cos, sin, scale, centerX),
                    screenY(originX - playerX, originZ - playerZ, cos, sin, scale, centerY),
                    COLOR_ORIGIN, minX, minY, maxX, maxY);
        }
        Destination destination = Navigation.target();
        if (destination != null) {
            emitMarker(last, vc,
                    screenX(destination.x() - playerX, destination.z() - playerZ, cos, sin, scale, centerX),
                    screenY(destination.x() - playerX, destination.z() - playerZ, cos, sin, scale, centerY),
                    COLOR_DESTINATION, minX, minY, maxX, maxY);
        }

        drawPlayerArrow(last, vc, centerX, centerY);

        // Every place gets its marker on the mini-map too, so places can be seen while travelling
        // without opening anything. The same list, colour and shape as the world map, from
        // Destinations and HudDraw, so the views cannot drift apart -- at this map's smaller size,
        // because a world-map marker covers a visible share of a panel this size.
        for (Destination place : Destinations.places()) {
            emitPlaceMarkerClipped(last, vc,
                    screenX(place.x() - playerX, place.z() - playerZ, cos, sin, scale, centerX),
                    screenY(place.x() - playerX, place.z() - playerZ, cos, sin, scale, centerY),
                    minX, minY, maxX, maxY);
        }

        drawMiniMapLabels(graphics, mc, network, playerX, playerZ, cos, sin, scale, centerX, centerY,
                minX, minY, maxX, maxY);

        // North indicator: with the map rotated to the player's heading, "up" is not north, so the
        // direction has to be shown explicitly.
        double northX = sin;
        double northY = -cos;
        double markerX = centerX + northX * (MAP_SIZE / 2.0 - 7);
        double markerY = centerY + northY * (MAP_SIZE / 2.0 - 7);
        if (markerX >= minX && markerX <= maxX && markerY >= minY && markerY <= maxY) {
            drawNorthMarker(graphics, mc, markerX, markerY);
        }

        pose.popPose();
        graphics.flush();
    }

    /**
     * Road names on the mini-map, so a route can be read without opening the full map.
     *
     * <p>Only named roads: most of a fresh network is unnamed, and a placeholder on all of them
     * would bury the names that exist. Labels that do not fit inside the panel, or that would
     * overprint one already placed, are dropped -- the map is 120 px across and two names in the
     * same corner would smear into each other.
     */
    private static void drawMiniMapLabels(GuiGraphics graphics, Minecraft mc, RoadNetwork network,
                                          double playerX, double playerZ, double cos, double sin,
                                          double scale, double centerX, double centerY,
                                          double minX, double minY, double maxX, double maxY) {
        List<double[]> placed = new ArrayList<>();
        for (RoadSegment segment : network.segmentsSnapshot()) {
            String name = segment.name();
            if (name == null) {
                continue;
            }
            List<Integer> chain = RoadChains.chainContaining(network, segment.id());
            if (RoadChains.middleSegment(chain) != segment.id()) {
                continue;
            }
            double[] mid = segment.midpoint();
            if (Math.abs(mid[0] - playerX) > VIEW_RADIUS || Math.abs(mid[1] - playerZ) > VIEW_RADIUS) {
                continue;
            }
            double x = screenX(mid[0] - playerX, mid[1] - playerZ, cos, sin, scale, centerX);
            double y = screenY(mid[0] - playerX, mid[1] - playerZ, cos, sin, scale, centerY);

            // Along the line, and the angle has to be taken through this map's own rotation: the panel
            // turns with the player, so a road's direction on screen is not its direction in the
            // world. The linear part of the map transform applied to the road's own world direction
            // gives the screen direction; the centre and the scale cancel out of an angle.
            int last = segment.vertexCount() - 1;
            double wdx = segment.x(last) - segment.x(0);
            double wdz = segment.z(last) - segment.z(0);
            float angle = HudDraw.labelAngle(wdx * cos - wdz * sin, wdx * sin + wdz * cos);

            int width = mc.font.width(name);
            double[] extents = HudDraw.rotatedExtents(width, LABEL_HEIGHT, angle);
            // The box that is actually painted: the text is centred on the same point it was before,
            // so rotating it does not shift it off the road.
            double centreY = y - 4 + LABEL_HEIGHT / 2.0;
            double boxX = x - extents[0] / 2.0;
            double boxY = centreY - extents[1] / 2.0;
            if (boxX < minX + 1 || boxX + extents[0] > maxX - 1
                    || boxY < minY + 1 || boxY + extents[1] > maxY - 1) {
                continue;
            }
            boolean clash = false;
            for (double[] other : placed) {
                if (boxX < other[0] + other[2] && other[0] < boxX + extents[0]
                        && boxY < other[1] + other[3] && other[1] < boxY + extents[1]) {
                    clash = true;
                    break;
                }
            }
            if (clash) {
                continue;
            }
            placed.add(new double[] {boxX, boxY, extents[0], extents[1]});

            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(x, centreY, 0.0);
            pose.mulPose(Axis.ZP.rotation(angle));
            graphics.drawString(mc.font, name, -width / 2, -4, COLOR_MAP_LABEL, false);
            pose.popPose();
        }
    }

    /**
     * Whether a one-block piece of a road is wholly outside the reach box around the player.
     *
     * <h2>Why "wholly", and why the box is generous</h2>
     * This is only a guard to keep far-away geometry out of the clipper; {@code emitClipped} is what
     * actually clips. The previous test asked whether <em>either</em> end was beyond the bound, which
     * threw away a road running in from outside the map even though the part inside it belonged on
     * screen -- a road at the edge of the map vanishing. A guard that is too generous costs a handful
     * of clipped quads; one that is too strict costs a visible road, so the bound is set at the map's
     * own corner distance rather than at its half-width.
     */
    private static boolean outsideReach(double ax, double az, double bx, double bz,
                                        double playerX, double playerZ, double reach) {
        return Math.min(ax, bx) > playerX + reach || Math.max(ax, bx) < playerX - reach
                || Math.min(az, bz) > playerZ + reach || Math.max(az, bz) < playerZ - reach;
    }

    /** A filled dart showing which way the player is facing: always up, given the rotation. */
    private static void drawPlayerArrow(PoseStack.Pose pose, VertexConsumer vc,
                                        double cx, double cy) {
        double size = 4.5;
        emitQuad(pose, vc,
                cx, cy - size,
                cx + size * 0.75, cy + size * 0.75,
                cx, cy + size * 0.3,
                cx - size * 0.75, cy + size * 0.75,
                0xFFFFFFFF);
    }

    private static void drawNorthMarker(GuiGraphics graphics, Minecraft mc, double x, double y) {
        graphics.drawString(mc.font, "N", (int) Math.round(x) - 2, (int) Math.round(y) - 4,
                0xFFD0D8E0, false);
    }

    // ------------------------------------------------------------------- info

    private static void drawInfo(GuiGraphics graphics, Minecraft mc, int x, int y) {
        Font font = mc.font;
        Destination destination = Navigation.target();
        if (destination == null) {
            return;
        }

        if (Navigation.showArrival()) {
            drawTurnArrow(graphics, x + 11, y + 12, Double.NaN, COLOR_OK);
            graphics.drawString(font, Component.translatable("hud.howtogo.hud_arrived").getString(),
                    x + READOUT_TEXT_X, y + 6, COLOR_OK, false);
            graphics.drawString(font, destination.name(), x + READOUT_TEXT_X, y + 18,
                    COLOR_SECONDARY_TEXT, false);
        } else if (!Navigation.route().isPresent()) {
            drawTurnArrow(graphics, x + 11, y + 12, Double.NaN, COLOR_WARN);
            graphics.drawString(font, Component.translatable("hud.howtogo.hud_noroute").getString(),
                    x + READOUT_TEXT_X, y + 6, COLOR_WARN, false);
        } else {
            drawRouteReadout(graphics, mc, x, y);
        }

        // Last, and text, so nothing follows it through the quad consumer the arrow opened.
        drawNarratorHint(graphics, font, x, y + READOUT_HINT_Y);
    }

    /** The turn, the distance, the progress bar and the remaining line: the live trip readout. */
    private static void drawRouteReadout(GuiGraphics graphics, Minecraft mc, int x, int y) {
        Font font = mc.font;
        // The wrong-way call takes the readout over when it is up: a player going the wrong way has
        // no use for the turn the route was going to give them next, and turning round at the next
        // junction is the instruction that actually gets them moving again.
        //
        // The wrong-way READING takes it over whether or not it could name a junction. Falling through
        // to the route's next turn when it could not is what produced "continue straight" for a player
        // travelling against the route; the plain U-turn, with no junction and no distance, is the
        // honest answer to not knowing where they can turn round.
        Navigation.Uturn uturn = Navigation.wrongWayUturn();
        boolean wrongWay = Navigation.wrongWay();
        Navigation.Instruction maneuver = wrongWay ? null : Navigation.nextManeuver();

        double maneuverDegrees = maneuver == null ? Double.NaN : maneuver.turnDegrees();
        // A U-turn is a U-turn whether the route asked for it or the player has turned round into
        // one, and on a highway neither can be called as one: there is nowhere to turn, so the same
        // junction is called as carrying on to it, and the arrow has to agree with the words rather
        // than with the angle the route came in at.
        boolean uturnCall = uturn != null || Navigation.isUturn(maneuverDegrees);
        boolean plainUturn = wrongWay && uturn == null;
        boolean highway = uturn != null ? uturn.highway()
                : ((uturnCall || plainUturn) && Navigation.onHighway());
        double arrowDegrees = (uturnCall || plainUturn) && highway ? Double.NaN
                : (uturn != null || plainUturn ? 180.0 : maneuverDegrees);
        drawTurnArrow(graphics, x + 11, y + 12, arrowDegrees, COLOR_ACCENT);

        // The number and the instruction are taken from the same object here and everywhere else that
        // shows them, so the distance can only ever count to the point the words are about. See
        // Navigation's note on that rule. With no junction named there is no point to count to, so the
        // slot shows nothing rather than a number from somewhere else.
        double distanceAhead = uturn != null ? uturn.distanceAhead()
                : (maneuver == null ? Double.NaN : maneuver.distanceAhead());

        // Distance to the turn is the number that matters, so it gets the large treatment. "Now" is
        // asked of Navigation rather than compared against a constant, so the panel flips to it at
        // the same distance the spoken announcement does.
        String distance = Double.isNaN(distanceAhead)
                ? "--"
                : (distanceAhead <= Navigation.turnNowDistance()
                        ? Component.translatable("hud.howtogo.hud_now").getString()
                        : Route.formatDistance(distanceAhead));
        graphics.drawString(font, distance, x + READOUT_TEXT_X, y + 2, COLOR_PRIMARY_TEXT, false);

        String instruction;
        if (uturnCall || plainUturn) {
            // No road named: a U-turn is not a turn onto anything, it is going back the way they came.
            instruction = Navigation.uturnAction(highway);
        } else if (maneuver == null) {
            instruction = Component.translatable("hud.howtogo.hud_straight").getString();
        } else if (!maneuver.namesTheRoad()) {
            // The turn is real, the name is not worth saying: it is the road they are already on.
            instruction = Navigation.turnPhrase(maneuverDegrees);
        } else {
            // Always name the road being entered, so the sentence never quietly loses its object.
            String road = maneuver.roadName() != null && !maneuver.roadName().isBlank()
                    ? maneuver.roadName()
                    : Navigation.unnamedRoad();
            instruction = Component.translatable("hud.howtogo.turn_onto",
                    Navigation.turnPhrase(maneuverDegrees), road).getString();
        }
        graphics.drawString(font,
                font.plainSubstrByWidth(instruction, MAP_SIZE - READOUT_TEXT_X - 2),
                x + READOUT_TEXT_X, y + 14, COLOR_SECONDARY_TEXT, false);

        int barY = y + 30;
        drawProgressBar(graphics, x, barY, MAP_SIZE, 5);

        String remaining = Component.translatable("hud.howtogo.hud_remaining_short",
                Route.formatDistance(Navigation.remainingLength()),
                Route.formatDuration(Navigation.remainingSeconds())).getString();
        int color = Navigation.isOffRoute() ? COLOR_WARN : COLOR_SECONDARY_TEXT;
        graphics.drawString(font, remaining, x, barY + 9, color, false);

        // Why this is a walking route when another mode is selected.
        String fallback = Navigation.fallbackHint();
        if (fallback != null) {
            graphics.drawString(font, font.plainSubstrByWidth(fallback, MAP_SIZE),
                    x, barY + 20, COLOR_WARN, false);
        }
    }

    /**
     * Says why nothing is being spoken, when the player has asked for speech and cannot have it.
     *
     * <p>Only a speech engine that failed to load can cause this, so it is rare -- but a toggle that
     * looks on and produces nothing reads as a broken feature, and the log is not where a player
     * looks. Wrapped rather than clipped: it is worded to fit one line at the panel's width, and the
     * second line is there so a translation that runs longer cannot be cut in half.
     */
    private static void drawNarratorHint(GuiGraphics graphics, Font font, int x, int y) {
        if (!Narration.unavailable()) {
            return;
        }
        List<FormattedCharSequence> lines = font.split(
                Component.translatable("hud.howtogo.narrator_unavailable"), MAP_SIZE);
        int count = Math.min(NARRATOR_HINT_LINES, lines.size());
        for (int i = 0; i < count; i++) {
            graphics.drawString(font, lines.get(i), x, y + i * NARRATOR_HINT_LINE_HEIGHT,
                    COLOR_WARN, false);
        }
    }

    /** Thin trip progress bar: filled by the fraction of the route already walked. */
    private static void drawProgressBar(GuiGraphics graphics, int x, int y, int width, int height) {
        double total = Navigation.route().totalLength();
        double fraction = total > 1.0E-6
                ? Math.max(0, Math.min(1, Navigation.travelled() / total))
                : 0;
        fillRounded(graphics, x, y, x + width, y + height, height / 2, COLOR_TRACK);
        int filled = (int) Math.round(width * fraction);
        if (filled > height) {
            fillRounded(graphics, x, y, x + filled, y + height, height / 2, COLOR_ACCENT);
        } else if (filled > 0) {
            graphics.fill(x, y, x + filled, y + height, COLOR_ACCENT);
        }
    }

    // ---------------------------------------------------------------- turn arrow

    /**
     * A navigation arrow: a shaft that bends toward the turn, ending in a solid triangular head.
     *
     * <p>Drawn from geometry rather than shipped as a texture, so it needs no asset and scales with
     * the UI. The head is a filled triangle rather than two swept barbs -- barbs read as a fork.
     *
     * <p>A U-turn is not drawn by this shape at all: it has its own, because a single bend cannot
     * express one. Pointing the head straight down sent the returning leg back along the shaft it had
     * just come up, so the icon was one line drawn twice over itself with the head buried in the
     * middle. See {@link #drawUturnArrow}.
     *
     * @param degrees signed turn angle, or NaN for "no turn ahead" (drawn as straight on)
     */
    private static void drawTurnArrow(GuiGraphics graphics, double cx, double cy,
                                      double degrees, int color) {
        PoseStack pose = graphics.pose();
        VertexConsumer vc = HudDraw.consumer(graphics);
        PoseStack.Pose last = pose.last();
        double thickness = 1.7;

        if (!Double.isNaN(degrees) && Math.abs(degrees) > 135) {
            drawUturnArrow(last, vc, cx, cy, thickness, color);
            graphics.flush();
            return;
        }

        double bend = arrowDirection(degrees);
        double dirX = Math.cos(bend);
        double dirY = Math.sin(bend);

        // The shaft always runs vertically up from the bottom, then bends into the turn.
        double cornerX = cx;
        double cornerY = cy - 1;
        double shaftBottomY = cy + 8;

        // Kept short so a left arrow still fits between the panel edge and the text column.
        double legLength = 4.0;
        double headLength = 5.5;
        double headHalf = 4.0;

        double tipX = cornerX + dirX * (legLength + headLength);
        double tipY = cornerY + dirY * (legLength + headLength);
        double baseX = tipX - dirX * headLength;
        double baseY = tipY - dirY * headLength;

        emitLine(last, vc, cornerX, shaftBottomY, cornerX, cornerY, thickness, color, 0xFF);
        emitDisc(last, vc, cornerX, cornerY, thickness, color, 0xFF);
        emitLine(last, vc, cornerX, cornerY, baseX, baseY, thickness, color, 0xFF);

        double perpX = -dirY;
        double perpY = dirX;
        emitTriangle(last, vc,
                tipX, tipY,
                baseX + perpX * headHalf, baseY + perpY * headHalf,
                baseX - perpX * headHalf, baseY - perpY * headHalf, color);

        graphics.flush();
    }

    /**
     * A U-turn arrow: up one side, over the top, down the other side, head at the bottom.
     *
     * <h2>Why a U-turn cannot use the bend shape</h2>
     * The bend draws a shaft up from the bottom and then a leg out toward the turn. At 180 degrees
     * that leg runs straight back down along the shaft, so the two lines coincide exactly and the icon
     * becomes a line drawn over itself. Which way the arrow points is the whole content of the icon,
     * and a shape that retraces itself cannot show a direction at all.
     *
     * <p>Sized to the same cell as the bend arrow -- about fifteen pixels tall and seven wide -- so
     * swapping between the two does not shift the readout beside them. The two shoulders stand in for
     * the curve, because every primitive here is a straight line.
     */
    private static void drawUturnArrow(PoseStack.Pose pose, VertexConsumer vc,
                                       double cx, double cy, double thickness, int color) {
        double half = 3.5;
        double topY = cy - 6.0;
        double shoulderY = cy - 3.0;
        double rightBottomY = cy + 7.0;
        double headBaseY = cy + 1.0;
        double tipY = cy + 7.0;

        emitLine(pose, vc, cx + half, rightBottomY, cx + half, shoulderY, thickness, color, 0xFF);
        emitLine(pose, vc, cx + half, shoulderY, cx, topY, thickness, color, 0xFF);
        emitLine(pose, vc, cx, topY, cx - half, shoulderY, thickness, color, 0xFF);
        emitLine(pose, vc, cx - half, shoulderY, cx - half, headBaseY, thickness, color, 0xFF);
        emitDisc(pose, vc, cx + half, rightBottomY, thickness, color, 0xFF);
        // Round joins at every corner. Without them the three shoulders meet at sharp wedges with a
        // notch on the outside, which is the same artefact roads would have without joints -- see the
        // round caps in the road renderer's stroke.
        emitDisc(pose, vc, cx + half, shoulderY, thickness, color, 0xFF);
        emitDisc(pose, vc, cx, topY, thickness, color, 0xFF);
        emitDisc(pose, vc, cx - half, shoulderY, thickness, color, 0xFF);
        emitDisc(pose, vc, cx - half, headBaseY, thickness, color, 0xFF);

        emitTriangle(pose, vc,
                cx - half, tipY,
                cx - half * 2.0, headBaseY,
                cx, headBaseY, color);
    }

    /**
     * Screen-space direction the arrow head points, in radians, for a signed turn angle.
     *
     * <p>Straight on is up; positive angles are right turns, following Minecraft's axes.
     */
    private static double arrowDirection(double degrees) {
        if (Double.isNaN(degrees)) {
            return -Math.PI / 2;
        }
        double magnitude = Math.abs(degrees);
        if (magnitude <= 25) {
            return -Math.PI / 2;
        }
        // Nothing above 135 degrees arrives here: a U-turn is drawn by drawUturnArrow, which is the
        // only shape that can express one. It used to answer "straight down" for that case, and the
        // caller then drew a leg back along the shaft it had just come up.
        boolean right = degrees > 0;
        if (magnitude > 50) {
            return right ? 0 : Math.PI;
        }
        return right ? -Math.PI / 4 : -Math.PI * 3 / 4;
    }

    // ---------------------------------------------------------------- geometry

    private static double screenX(double dx, double dz, double cos, double sin,
                                  double scale, double centerX) {
        return centerX + (dx * cos - dz * sin) * scale;
    }

    private static double screenY(double dx, double dz, double cos, double sin,
                                  double scale, double centerY) {
        return centerY + (dx * sin + dz * cos) * scale;
    }

    /** Draws a marker, skipping it entirely when it falls outside the map. */
    private static void emitMarker(PoseStack.Pose pose, VertexConsumer vc, double x, double y,
                                   int argb, double minX, double minY, double maxX, double maxY) {
        if (x < minX || x > maxX || y < minY || y > maxY) {
            return;
        }
        emitDisc(pose, vc, x, y, 2.6, argb, 0xFF);
    }

    /** A place's marker, skipped entirely when it falls outside the map. */
    private static void emitPlaceMarkerClipped(PoseStack.Pose pose, VertexConsumer vc, double x,
                                              double y, double minX, double minY, double maxX,
                                              double maxY) {
        if (x < minX || x > maxX || y < minY || y > maxY) {
            return;
        }
        HudDraw.emitPlaceMarker(pose, vc, x, y, HudDraw.PLACE_MARKER_SMALL_PX * 0.5,
                HudDraw.COLOR_PLACE);
    }
}
