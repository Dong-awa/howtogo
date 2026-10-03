package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;

/**
 * Resolves a raw mouse position into a road vertex position.
 *
 * <p>Snapping is evaluated in <b>screen</b> space, not world space. A fixed world radius would be
 * unusable at high zoom (everything is within 8 blocks) and useless at low zoom (nothing is), so
 * the catch radius is a pixel budget that feels the same at every zoom level.
 *
 * <p>Priority: existing node &gt; point on an existing segment &gt; 45-degree angle constraint
 * relative to the node being drawn from &gt; free placement.
 */
public final class RoadSnapper {

    public enum Kind {
        FREE,
        NODE,
        SEGMENT,
        ANGLE,
        /**
         * On an automatically detected rail, as a position only.
         *
         * <p>Its own kind rather than {@link #SEGMENT} because what the caller may do differs: a
         * hand-drawn segment can be split and joined at the cursor, while a rail is a reading of the
         * world that the editor does not own and must only be able to land on. Reporting it as a
         * plain segment would send it into the split path, which finds no such segment in the
         * editor's network and silently does nothing -- a click that places no point at all.
         */
        RAIL
    }

    /**
     * Catch radius, in screen pixels, for snapping onto an existing node.
     *
     * <p>Kept fairly tight on purpose: a generous radius makes precise placement impossible and is
     * the usual complaint about map editors. Alt bypasses snapping entirely when more precision
     * than this allows is needed.
     */
    public static final double NODE_SNAP_PX = 9.0;
    /** Catch radius, in screen pixels, for snapping onto the interior of a segment. */
    public static final double SEGMENT_SNAP_PX = 6.0;
    /** Angular tolerance, in degrees, for the 45-degree constraint. */
    public static final double ANGLE_SNAP_DEG = 5.0;

    /**
     * @param x           snapped world X
     * @param z           snapped world Z
     * @param kind        which rule fired
     * @param nodeId      node that was snapped to, or {@link RoadSegment#NO_NODE}
     * @param segmentId   segment that was snapped to, or {@link RoadSegment#NO_SEGMENT}
     * @param vertexIndex index at which a new vertex would be inserted into that segment
     */
    public record Result(double x, double z, Kind kind, int nodeId, int segmentId, int vertexIndex) {

        public static Result free(double x, double z) {
            return new Result(x, z, Kind.FREE, RoadSegment.NO_NODE, RoadSegment.NO_SEGMENT, -1);
        }
    }

    private RoadSnapper() {
    }

    public static Result snap(RoadNetwork network, double worldX, double worldZ, int chainNodeId) {
        if (!MapViewState.isValid()) {
            return Result.free(worldX, worldZ);
        }

        Result node = snapToNode(network, worldX, worldZ);
        if (node != null) {
            return node;
        }

        Result segment = snapToSegment(network, worldX, worldZ);
        if (segment != null) {
            return segment;
        }

        Result angle = applyAngleConstraint(network, worldX, worldZ, chainNodeId);
        if (angle != null) {
            return angle;
        }

        return Result.free(worldX, worldZ);
    }

    private static Result snapToNode(RoadNetwork network, double worldX, double worldZ) {
        double mouseSx = MapViewState.toScreenX(worldX);
        double mouseSz = MapViewState.toScreenZ(worldZ);

        RoadNode best = null;
        double bestDistSq = NODE_SNAP_PX * NODE_SNAP_PX;
        for (RoadNode node : network.nodes()) {
            double dx = MapViewState.toScreenX(node.x()) - mouseSx;
            double dz = MapViewState.toScreenZ(node.z()) - mouseSz;
            double d = dx * dx + dz * dz;
            if (d <= bestDistSq) {
                bestDistSq = d;
                best = node;
            }
        }
        if (best == null) {
            return null;
        }
        return new Result(best.x(), best.z(), Kind.NODE, best.id(), RoadSegment.NO_SEGMENT, -1);
    }

    private static Result snapToSegment(RoadNetwork network, double worldX, double worldZ) {
        double mouseSx = MapViewState.toScreenX(worldX);
        double mouseSz = MapViewState.toScreenZ(worldZ);

        RoadSegment bestSegment = null;
        double bestDistSq = SEGMENT_SNAP_PX * SEGMENT_SNAP_PX;
        double bestWorldX = 0;
        double bestWorldZ = 0;
        int bestIndex = -1;

        for (RoadSegment segment : network.segments()) {
            for (int i = 1; i < segment.vertexCount(); i++) {
                double ax = MapViewState.toScreenX(segment.x(i - 1));
                double az = MapViewState.toScreenZ(segment.z(i - 1));
                double bx = MapViewState.toScreenX(segment.x(i));
                double bz = MapViewState.toScreenZ(segment.z(i));

                double ex = bx - ax;
                double ez = bz - az;
                double lenSq = ex * ex + ez * ez;
                if (lenSq < 1.0E-9) {
                    continue;
                }
                double t = ((mouseSx - ax) * ex + (mouseSz - az) * ez) / lenSq;
                t = Math.max(0.0, Math.min(1.0, t));

                double px = ax + ex * t;
                double pz = az + ez * t;
                double dx = px - mouseSx;
                double dz = pz - mouseSz;
                double d = dx * dx + dz * dz;

                if (d <= bestDistSq) {
                    bestDistSq = d;
                    bestSegment = segment;
                    bestIndex = i;
                    bestWorldX = segment.x(i - 1) + (segment.x(i) - segment.x(i - 1)) * t;
                    bestWorldZ = segment.z(i - 1) + (segment.z(i) - segment.z(i - 1)) * t;
                }
            }
        }

        if (bestSegment == null) {
            return null;
        }
        return new Result(bestWorldX, bestWorldZ, Kind.SEGMENT, RoadSegment.NO_NODE,
                bestSegment.id(), bestIndex);
    }

    private static Result applyAngleConstraint(RoadNetwork network, double worldX, double worldZ, int chainNodeId) {
        RoadNode from = chainNodeId == RoadSegment.NO_NODE ? null : network.node(chainNodeId);
        if (from == null) {
            return null;
        }
        double dx = worldX - from.x();
        double dz = worldZ - from.z();
        double length = Math.hypot(dx, dz);
        if (length < 1.0E-3) {
            return null;
        }

        double angle = Math.atan2(dz, dx);
        double step = Math.PI / 4.0;
        double snapped = Math.round(angle / step) * step;

        double deltaDeg = Math.toDegrees(Math.abs(angle - snapped));
        if (deltaDeg > ANGLE_SNAP_DEG) {
            return null;
        }

        double sx = from.x() + Math.cos(snapped) * length;
        double sz = from.z() + Math.sin(snapped) * length;
        return new Result(Math.round(sx), Math.round(sz), Kind.ANGLE,
                RoadSegment.NO_NODE, RoadSegment.NO_SEGMENT, -1);
    }
}
