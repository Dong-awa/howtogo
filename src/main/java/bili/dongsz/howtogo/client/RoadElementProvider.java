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
 * <p>The list is rebuilt per frame. Road networks are small and the rebuild is a single linear
 * pass, so this trades a negligible cost for never handing Xaero a stale view of the network while
 * the player is editing. Spatial indexing is a P3 concern.
 *
 * <p>Overlays are appended last so they draw on top of the roads.
 */
public final class RoadElementProvider extends ElementRenderProvider<RoadElement, RoadRenderContext> {

    private final List<RoadElement> buffer = new ArrayList<>();
    private int index;

    /**
     * How far outside the screen the view rectangle is extended before anything is dropped.
     *
     * <p>A quarter of the screen on each side. The margin has to be generous because the projection
     * this reads was last updated during the *previous* render pass: culling against a one-pass-old
     * view with a tight margin would make roads flicker in and out at the edges while the player pans
     * -- which is a worse bug than the one this culling exists to fix.
     */
    private static final double VIEW_MARGIN_FRACTION = 0.25;

    /**
     * Whether a polyline lies entirely outside the given world rectangle.
     *
     * <p>Tested by bounding box, and only rejected when the whole box is outside: a road that runs
     * across the view from one side to the other has neither end inside it, so an "is any vertex
     * visible" test would throw away exactly the longest, most visible roads. A box that grazes the
     * view without the line entering it costs one wasted element, which is the cheap direction to be
     * wrong in -- this is a performance guard, and Xaero still does the precise culling downstream.
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

        // Where the view is looking, in world blocks, with a generous margin. Everything outside it
        // is dropped here instead of being wrapped in an element, handed to Xaero and discarded
        // there: on a large network that walk *is* the frame cost, and a road nobody can see does not
        // need an object.
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
        }
        // Create's tracks go in with the roads rather than behind a second renderer: they are rails
        // of the same class, drawn in the same colour with the same code, so the map shows one kind
        // of line. They are the coarse layer, a handful of polylines, so appending them per frame
        // costs one pass over a list that is small by construction.
        Collection<RoadSegment> layer = RailTrackStore.segments();
        if (!layer.isEmpty()) {
            // TEMPORARY rail diagnostic: one enumeration of the layer, attributed to the render
            // location that asked for it, which is how the per-location line explains itself.
            RailTrackStore.noteMapPass(location == null ? -1 : location.getIndex());
        }
        for (RoadSegment segment : layer) {
            if (haveView && outsideView(segment, minWorldX, minWorldZ, maxWorldX, maxWorldZ)) {
                continue;
            }
            buffer.add(RoadElement.of(segment));
            // TEMPORARY rail diagnostic: one element of the layer offered in this pass.
            RailTrackStore.noteElementOffered();
        }

        if (Navigation.target() != null) {
            // Added whenever a destination is set, not just when a route was found, so the HUD can
            // report that routing failed instead of staying silent.
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

        // Always last, in every mode. Names are a property of the map, not of editing or of an
        // active trip, so they are drawn whether or not either overlay is up -- and from the tail
        // so a stroke drawn later can never end up on top of the text.
        //
        // Added unconditionally: gating this on haveView meant the element could be missing from
        // the frame while the route overlay was still present, which is exactly when a name is
        // most useful.
        if (haveView) {
            RoadElement.LABELS.setOverlayAnchor(MapViewState.cameraX(), MapViewState.cameraZ());
        }
        buffer.add(RoadElement.LABELS);

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
