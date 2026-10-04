package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.road.RoadSpatialIndex;

/**
 * Resolves a raw mouse position into a road vertex position.
 *
 * <p>Snapping is evaluated in <b>screen</b> space, not world space. A fixed world radius would be
 * unusable at high zoom (everything is within 8 blocks) and useless at low zoom (nothing is), so
 * the catch radius is a pixel budget that feels the same at every zoom level.
 *
 * <p>Priority: existing node &gt; point on an existing segment &gt; 45-degree angle constraint
 * relative to the node being drawn from &gt; free placement.
 *
 * <h2>How the near-enough points are found</h2>
 * The catch radius is a screen distance and the geometry is in world blocks, so the radius is
 * converted to blocks against the viewport and handed to {@link RoadSpatialIndex}, which reads the
 * grid cells around the cursor instead of walking the network. The conversion used to be the other
 * way round -- every node and every vertex projected to the screen and measured there -- which was
 * exact but linear in the network, on every frame.
 *
 * <p>The two axes are converted separately and the test stays elliptical in blocks, because the
 * viewport's pixels-per-block can differ between them. The result is the same point the projected
 * walk would have chosen; {@link RoadSpatialIndex} carries the argument for why.
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

    /**
     * The index most recently used, kept for the network revision it was built from.
     *
     * <p>The editor asks once a frame and the rail layer asks again whenever the hand-drawn roads
     * were not close enough, so a build per query would spend the saving on rebuilding a structure
     * that has not changed. The revision is a counter rather than the reference because the editor
     * mutates one network in place for the whole session: a cache keyed on the reference alone would
     * keep serving a reading of roads that have since moved.
     */
    private static RoadSpatialIndex cachedIndex;
    private static RoadNetwork cachedNetwork;
    private static int cachedRevision = Integer.MIN_VALUE;

    private RoadSnapper() {
    }

    private static RoadSpatialIndex indexFor(RoadNetwork network) {
        if (cachedIndex == null || cachedNetwork != network || cachedRevision != network.revision()) {
            cachedIndex = RoadSpatialIndex.of(network);
            cachedNetwork = network;
            cachedRevision = network.revision();
        }
        return cachedIndex;
    }

    /**
     * Drops the cached reading, for a caller that has changed a network it does not own the index for.
     *
     * <p>Not needed for an edit made through {@link bili.dongsz.howtogo.road.RoadEditor}, which bumps
     * the network's revision and so invalidates this by itself. It is here for a layer rebuilt
     * wholesale, where the sensible thing is to say so rather than to rely on the builder having
     * remembered.
     */
    public static void invalidateIndex() {
        cachedIndex = null;
        cachedNetwork = null;
        cachedRevision = Integer.MIN_VALUE;
    }

    public static Result snap(RoadNetwork network, double worldX, double worldZ, int chainNodeId) {
        Viewport viewport = Viewport.current();
        if (viewport == null) {
            return Result.free(worldX, worldZ);
        }

        Result node = snapToNode(network, worldX, worldZ, viewport);
        if (node != null) {
            return node;
        }

        Result segment = snapToSegment(network, worldX, worldZ, viewport);
        if (segment != null) {
            return segment;
        }

        Result angle = applyAngleConstraint(network, worldX, worldZ, chainNodeId);
        if (angle != null) {
            return angle;
        }

        return Result.free(worldX, worldZ);
    }

    /**
     * The viewport as a blocks-per-pixel conversion.
     *
     * <p>Snapping measures in pixels and the index measures in blocks, so the two have to meet
     * somewhere; this is that place. It exists so that the conversion is done once per query in one
     * direction (pixels to blocks) rather than once per candidate in the other (blocks to pixels).
     */
    private record Viewport(double blocksPerPixelX, double blocksPerPixelZ) {

        /** Null when there is no usable viewport, which is when snapping cannot mean anything. */
        static Viewport current() {
            if (!MapViewState.isValid()) {
                return null;
            }
            double perBlockX = Math.abs(MapViewState.pixelsPerBlockX());
            double perBlockZ = Math.abs(MapViewState.pixelsPerBlockZ());
            if (perBlockX < 1.0E-9 || perBlockZ < 1.0E-9) {
                return null;
            }
            return new Viewport(1.0 / perBlockX, 1.0 / perBlockZ);
        }

        /** The world radius a pixel catch radius corresponds to, per axis. */
        double radiusX(double pixels) {
            return pixels * blocksPerPixelX;
        }

        double radiusZ(double pixels) {
            return pixels * blocksPerPixelZ;
        }
    }

    private static Result snapToNode(RoadNetwork network, double worldX, double worldZ,
                                     Viewport viewport) {
        double radiusX = viewport.radiusX(NODE_SNAP_PX);
        double radiusZ = viewport.radiusZ(NODE_SNAP_PX);
        // The index ranks candidates by plain distance, over a radius wide enough to hold the whole
        // ellipse: every point the elliptical test would accept is inside this circle, so the nearest
        // node the index can miss is none. It may hand back a node the ellipse then refuses, and that
        // is the one case the test below exists for -- the node it hands back is no further than the
        // node the ellipse wanted, so refusing it cannot hide that one.
        RoadNode best = indexFor(network).nearestNode(worldX, worldZ, Math.max(radiusX, radiusZ));
        if (best == null) {
            return null;
        }
        double dx = worldX - best.x();
        double dz = worldZ - best.z();
        // The same elliptical test the per-candidate projection used to make: each offset over its
        // own axis's radius is the screen offset in pixels, so the sum of squares is the squared
        // screen distance.
        double sx = dx / radiusX;
        double sz = dz / radiusZ;
        if (sx * sx + sz * sz > NODE_SNAP_PX * NODE_SNAP_PX) {
            return null;
        }
        return new Result(best.x(), best.z(), Kind.NODE, best.id(), RoadSegment.NO_SEGMENT, -1);
    }

    private static Result snapToSegment(RoadNetwork network, double worldX, double worldZ,
                                        Viewport viewport) {
        double radiusX = viewport.radiusX(SEGMENT_SNAP_PX);
        double radiusZ = viewport.radiusZ(SEGMENT_SNAP_PX);
        double pixelSq = SEGMENT_SNAP_PX * SEGMENT_SNAP_PX;
        // Only the radius is stated; which of two equally near points is the answer is the index's
        // own rule, so that a click at a shared vertex does not depend on the grid's reading order.
        RoadSpatialIndex.Coverage coverage = (dx, dz) -> {
            double sx = dx / radiusX;
            double sz = dz / radiusZ;
            return sx * sx + sz * sz <= pixelSq;
        };

        RoadSpatialIndex.SegmentHit hit = indexFor(network).nearestOnSegment(worldX, worldZ,
                Math.max(radiusX, radiusZ), coverage);
        if (hit == null) {
            return null;
        }
        return new Result(hit.x(), hit.z(), Kind.SEGMENT, RoadSegment.NO_NODE,
                hit.segment().id(), hit.edgeIndex());
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
