package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.road.RoadSpatialIndex;

/**
 * Resolves a raw mouse position into a road vertex position.
 * Snapping works in screen space: the catch radius is a pixel budget converted to blocks per axis
 * against the viewport, so it feels the same at every zoom and the test stays elliptical in blocks.
 * Priority: existing node > point on an existing segment > 45-degree constraint > free placement.
 */
public final class RoadSnapper {

    public enum Kind {
        FREE,
        NODE,
        SEGMENT,
        ANGLE,
        /**
         * On an automatically detected rail, as a position only. Its own kind because a rail may only be
         * landed on, not split or joined like a hand-drawn segment.
         */
        RAIL,

        /**
         * On a RoadWeaver road or structure, as a position only: the geometry belongs to RoadWeaver, so
         * nothing here may split, join or move it.
         */
        ROADWEAVER
    }

    /** Catch radius in screen pixels for snapping onto an existing node; Alt bypasses snapping. */
    public static final double NODE_SNAP_PX = 9.0;
    /** Catch radius in screen pixels for snapping onto a segment's interior. */
    public static final double SEGMENT_SNAP_PX = 6.0;
    /** Angular tolerance in degrees for the 45-degree constraint. */
    public static final double ANGLE_SNAP_DEG = 5.0;

    /**
     * @param x           snapped world X
     * @param z           snapped world Z
     * @param kind        which rule fired
     * @param nodeId      node that was snapped to, or RoadSegment.NO_NODE
     * @param segmentId   segment that was snapped to, or RoadSegment.NO_SEGMENT
     * @param vertexIndex index at which a new vertex would be inserted into that segment
     */
    public record Result(double x, double z, Kind kind, int nodeId, int segmentId, int vertexIndex) {

        public static Result free(double x, double z) {
            return new Result(x, z, Kind.FREE, RoadSegment.NO_NODE, RoadSegment.NO_SEGMENT, -1);
        }
    }

    /**
     * The index last built and the network revision it was built from. The editor mutates one network
     * in place, so the key is a revision counter rather than the reference.
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

    /** Drops the cached reading, for a caller that changed a network it does not own the index for. */
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

    /** World radius a pixel catch radius means at the current zoom, or zero when there is no viewport. */
    public static double worldRadius(double pixels) {
        Viewport viewport = Viewport.current();
        return viewport == null ? 0.0 : Math.max(viewport.radiusX(pixels), viewport.radiusZ(pixels));
    }

    /**
     * Nearest of a list of points, as a position-only snap of the given kind; same pixel budget and
     * elliptical test. Returns null when nothing is in range.
     */
    public static Result snapToPoints(java.util.List<double[]> points, double worldX, double worldZ,
                                      double catchPixels, Kind kind) {
        Viewport viewport = Viewport.current();
        if (viewport == null || points == null || points.isEmpty()) {
            return null;
        }
        double radiusX = viewport.radiusX(catchPixels);
        double radiusZ = viewport.radiusZ(catchPixels);
        double bestPixelSq = catchPixels * catchPixels;
        double[] best = null;
        for (double[] point : points) {
            double sx = (point[0] - worldX) / radiusX;
            double sz = (point[1] - worldZ) / radiusZ;
            double pixelSq = sx * sx + sz * sz;
            if (pixelSq <= bestPixelSq) {
                bestPixelSq = pixelSq;
                best = point;
            }
        }
        return best == null ? null
                : new Result(best[0], best[1], kind, RoadSegment.NO_NODE, RoadSegment.NO_SEGMENT, -1);
    }

    /**
     * Blocks-per-pixel conversion, so pixels-to-blocks happens once per query rather than once per
     * candidate.
     */
    private record Viewport(double blocksPerPixelX, double blocksPerPixelZ) {

        /** Null when there is no usable viewport. */
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

        /** World radius a pixel catch radius means on this axis. */
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
        // The index ranks by plain distance over a radius wide enough to hold the ellipse, so it may
        // return a node the ellipse then refuses.
        RoadNode best = indexFor(network).nearestNode(worldX, worldZ, Math.max(radiusX, radiusZ));
        if (best == null) {
            return null;
        }
        double dx = worldX - best.x();
        double dz = worldZ - best.z();
        // Each offset over its own axis radius is the screen offset in pixels; the sum of squares is the
        // squared screen distance.
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
        // Only the radius is stated; ties are the index's own rule.
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
