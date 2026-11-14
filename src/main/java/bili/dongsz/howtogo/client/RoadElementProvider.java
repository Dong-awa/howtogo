package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.road.RoadSegment;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Feeds road elements to Xaero's render pipeline one at a time.
 *
 * The list is rebuilt per frame -- networks are small and the pass is linear -- so Xaero never sees a stale view while editing. Overlays are appended last; roads are sorted by storey first (see RoadElement#sortForDraw).
 */
public final class RoadElementProvider extends ElementRenderProvider<RoadElement, RoadRenderContext> {

    private final List<RoadElement> buffer = new ArrayList<>();
    private int index;

    /**
     * How far outside the screen the view rectangle is extended before culling: a quarter of the screen per side, generous because the projection read here is one render pass old and a tight margin would make roads flicker at the edges while panning.
     */
    private static final double VIEW_MARGIN_FRACTION = 0.25;

    /**
     * Whether a polyline lies entirely outside the given world rectangle.
     *
     * Bounding-box test, rejecting only when the whole box is out: an "any vertex visible" test would drop the longest roads crossing the view. A grazing box costs one wasted element; Xaero culls precisely downstream.
     */
    private static boolean outsideView(RoadSegment segment, double minX, double minZ,
                                       double maxX, double maxZ) {
        int lowestX = Integer.MAX_VALUE;
        int highestX = Integer.MIN_VALUE;
        int lowestZ = Integer.MAX_VALUE;
        int highestZ = Integer.MIN_VALUE;
        for (int i = 0; i < segment.vertexCount(); i++) {
            int x = segment.x(i);
            int z = segment.z(i);
            if (x < lowestX) {
                lowestX = x;
            }
            if (x > highestX) {
                highestX = x;
            }
            if (z < lowestZ) {
                lowestZ = z;
            }
            if (z > highestZ) {
                highestZ = z;
            }
        }
        return highestX < minX || lowestX > maxX || highestZ < minZ || lowestZ > maxZ;
    }

    @Override
    public void begin(ElementRenderLocation location, RoadRenderContext context) {
        buffer.clear();
        int roads = 0;
        int rails = 0;

        // View centre and a generous margin, in world blocks. Dropping here beats wrapping an element for
        // Xaero to discard: on a large network that walk is the frame cost.
        boolean haveView = MapViewState.isValid();
        double minWorldX = 0;
        double maxWorldX = 0;
        double minWorldZ = 0;
        double maxWorldZ = 0;
        if (haveView) {
            double marginX = MapViewState.screenWidth() * VIEW_MARGIN_FRACTION;
            double marginY = MapViewState.screenHeight() * VIEW_MARGIN_FRACTION;
            minWorldX = MapViewState.toWorldX(-marginX);
            maxWorldX = MapViewState.toWorldX(MapViewState.screenWidth() + marginX);
            minWorldZ = MapViewState.toWorldZ(-marginY);
            maxWorldZ = MapViewState.toWorldZ(MapViewState.screenHeight() + marginY);
        }

        for (RoadSegment segment : RoadStore.get().segments()) {
            if (haveView && outsideView(segment, minWorldX, minWorldZ, maxWorldX, maxWorldZ)) {
                continue;
            }
            buffer.add(RoadElement.of(segment));
            roads++;
        }
        // Create's tracks join the roads instead of a second renderer: same class, same colour, same drawing
        // code. A small coarse layer, so appending per frame costs one cheap pass.
        Collection<RoadSegment> layer = RailTrackStore.segments();
        if (!layer.isEmpty()) {
            // Rail diagnostic: one enumeration of the layer, attributed to the render location that asked for it.
            RailTrackStore.noteMapPass(location == null ? -1 : location.getIndex());
        }
        for (RoadSegment segment : layer) {
            if (haveView && outsideView(segment, minWorldX, minWorldZ, maxWorldX, maxWorldZ)) {
                continue;
            }
            buffer.add(RoadElement.of(segment));
            rails++;
            // Rail diagnostic: one element of the layer offered in this pass.
            RailTrackStore.noteElementOffered();
        }

        // Addon layers drawn with the roads, for the same reason.
        // Offered after the player's roads so the final sort puts an addon bridge above the road it crosses.
        // The list is replaced whole when a layer is re-read, so the held reference cannot change.
        for (RoadSegment segment : AddonRoadStore.segments()) {
            if (haveView && outsideView(segment, minWorldX, minWorldZ, maxWorldX, maxWorldZ)) {
                continue;
            }
            buffer.add(RoadElement.of(segment));
        }

        // RoadWeaver's roads, drawn with the roads for the same reason. Read once per pass; the list is
        // replaced whole when the layer is re-read.
        for (RoadSegment segment : RoadWeaverLayer.drawnSegments()) {
            if (haveView && outsideView(segment, minWorldX, minWorldZ, maxWorldX, maxWorldZ)) {
                continue;
            }
            buffer.add(RoadElement.of(segment));
        }

        // MTR's marks, in their own pass before overlays.
        // One element per rail made the map slow: whole railways are tens of thousands of near-pixel elements.
        // RoadElementRenderer draws them from the lines' own polylines, thinned and culled per stretch.
        // Own pass because roads are under them and the route and lines are over.
        MapPassReport.elements(roads, rails);
        if (haveView) {
            RoadElement.MARKS.setOverlayAnchor(MapViewState.cameraX(), MapViewState.cameraZ());
        }
        buffer.add(RoadElement.MARKS);

        if (Navigation.target() != null) {
            // Added whenever a destination is set, so the HUD can report routing failure.
            if (haveView) {
                RoadElement.ROUTE.setOverlayAnchor(MapViewState.cameraX(), MapViewState.cameraZ());
            }
            buffer.add(RoadElement.ROUTE);
        }

        if (RoadEditSession.isActive()) {
            if (haveView) {
                RoadElement.EDIT_UI.setOverlayAnchor(MapViewState.cameraX(), MapViewState.cameraZ());
            }
            buffer.add(RoadElement.EDIT_UI);
        }

        // Names are always last: they belong to the map, not editing, and the tail keeps strokes off the text.
        // Unconditional, so names are not dropped exactly when the route overlay is up.
        if (haveView) {
            RoadElement.LABELS.setOverlayAnchor(MapViewState.cameraX(), MapViewState.cameraZ());
        }
        buffer.add(RoadElement.LABELS);

        // Storey sort once per frame: gather order says nothing about height; overlays sort after every road.
        // See RoadElement#sortForDraw.
        RoadElement.sortForDraw(buffer);

        index = 0;
    }

    @Override
    public boolean hasNext(ElementRenderLocation location, RoadRenderContext context) {
        return index < buffer.size();
    }

    @Override
    public RoadElement getNext(ElementRenderLocation location, RoadRenderContext context) {
        return buffer.get(index++);
    }

    @Override
    public void end(ElementRenderLocation location, RoadRenderContext context) {
        index = 0;
    }
}
