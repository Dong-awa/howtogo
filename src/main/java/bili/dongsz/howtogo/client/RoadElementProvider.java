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

    @Override
    public void begin(ElementRenderLocation location, RoadRenderContext context) {
        buffer.clear();
        for (RoadSegment segment : RoadStore.get().segments()) {
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
            buffer.add(RoadElement.of(segment));
            // TEMPORARY rail diagnostic: one element of the layer offered in this pass.
            RailTrackStore.noteElementOffered();
        }

        boolean haveView = MapViewState.isValid();

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
