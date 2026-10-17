package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.client.CreateStationSource;
import bili.dongsz.howtogo.client.Destinations;
import bili.dongsz.howtogo.client.MtrStationSource;
import bili.dongsz.howtogo.client.MtrTransit;
import bili.dongsz.howtogo.client.PoiDestinationSource;
import bili.dongsz.howtogo.client.RailTrackStore;
import bili.dongsz.howtogo.client.RoadStore;
import bili.dongsz.howtogo.client.TransitLineStore;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.List;

/**
 * What the mod currently knows about the world the player is in: roads, lines, stations and the
 * machine-read rail layers.
 *
 * <h2>Snapshots, not live objects</h2>
 * Every accessor here copies. That is not caution for its own sake -- the two stores replace what they
 * hold when the player changes dimension or server, so a reference kept across a level change points
 * at the previous world's data, and a caller that mutates what it holds changes state the mod is
 * about to write to disk. Both are bugs that arrive much later than the line that caused them, so the
 * only object this API hands out is one nobody else is holding.
 *
 * <p>The cost is a copy per call, which is why these are queries and not per-frame drawing paths: the
 * mod's own renderers walk its live structures directly and are not expected to go through here.
 *
 * <h2>Threading</h2>
 * Client thread only. The machine-read layers are rebuilt on their own schedule and the MTR reader
 * keeps a worker of its own; calling from elsewhere would read a half-built layer. Every method here
 * is meant to be called from a tick, a screen or a renderer.
 *
 * <h2>Read-only is a fact, not a policy in this file</h2>
 * Create's and MTR's rails and lines belong to those mods; this mod reads them and never writes them
 * back. {@link #railLayers()} therefore reports them as not editable, and the write API (a later
 * addition) refuses them. The player's own roads are the only editable thing in this class, and they
 * are {@link #roadsSnapshot()}.
 */
public final class WorldDataApi {

    /** Id of Create's rail layer in {@link #railLayers()} and {@link #railLayerSegments(String)}. */
    public static final String LAYER_CREATE = "create";

    /** Id of MTR's marked-tracks layer in {@link #railLayers()} and {@link #railLayerSegments(String)}. */
    public static final String LAYER_MTR = "mtr";

    private WorldDataApi() {
    }

    /**
     * The player's road network for the dimension in play, as a copy.
     *
     * <p>Editable in principle -- it is the player's own data -- but editing the copy changes nothing:
     * the write path for it is the mod's own editor and, for addons, the write API.
     */
    public static RoadNetwork roadsSnapshot() {
        return RoadStore.get().deepCopy();
    }

    /**
     * The road network's revision, which changes whenever it does.
     *
     * <p>For a caller that keeps something derived from the roads -- a cache, an overlay, a picture --
     * and wants to know whether it is still current. Compare it with the value read beside the data,
     * not across a level change: a new world starts a fresh network whose revision starts over too.
     */
    public static int roadRevision() {
        return RoadStore.get().revision();
    }

    /**
     * The player's public transport lines for the dimension in play, as copies.
     *
     * <p>The lines read out of MTR are not here: they are MTR's, they are rebuilt from what MTR sends,
     * and they are offered as destinations and for planning without ever being saved with the player's
     * own. This is the list the mod's line editor edits.
     */
    public static List<TransitLine> linesSnapshot() {
        List<TransitLine> live = TransitLineStore.get();
        List<TransitLine> snapshot = new ArrayList<>(live.size());
        for (TransitLine line : live) {
            snapshot.add(line.copy());
        }
        return List.copyOf(snapshot);
    }

    /**
     * Every station the player could board at, from all three sources.
     *
     * <p>This is the one call that answers "where can I travel from", which is otherwise three
     * different questions asked of three different objects with three different names for the same
     * thing. Unnamed places are left out: a station with no name cannot be selected, searched for or
     * announced, so listing it would be listing something that does not work.
     *
     * <p>Order is stable and is the mod's own listing order: the player's stations, then Create's,
     * then MTR's.
     */
    public static List<StationRef> stations() {
        List<StationRef> result = new ArrayList<>();

        for (RoadNode node : RoadStore.get().nodes()) {
            if (node.type() == RoadNode.Type.POI
                    && node.placeKind() == PlaceKind.STATION
                    && node.name() != null
                    && !node.name().isBlank()) {
                result.add(new StationRef(node.name(), node.x(), node.y(), node.z(),
                        PoiDestinationSource.ID));
            }
        }
        for (RailTrackStore.Station station : RailTrackStore.stations()) {
            result.add(new StationRef(CreateStationSource.nameOf(station), station.x(), station.y(),
                    station.z(), CreateStationSource.ID));
        }
        for (MtrTransit.Station station : MtrTransit.stations()) {
            result.add(new StationRef(
                    Destinations.stationName(station.name(), station.x(), station.z()),
                    station.x(), station.y(), station.z(), MtrStationSource.ID));
        }
        return result;
    }

    /**
     * The machine-read rail layers, described.
     *
     * <p>Entries are present whether or not the mod that owns them is installed; a layer with nothing
     * in it has a segment count of zero. An addon that draws its own layer's contents wants
     * {@link #railLayerSegments(String)} instead.
     */
    public static List<RailLayerRef> railLayers() {
        return List.of(
                new RailLayerRef(LAYER_CREATE, "hud.howtogo.raillayer.create",
                        RailTrackStore.segments().size(), false),
                new RailLayerRef(LAYER_MTR, "hud.howtogo.raillayer.mtr",
                        MtrTransit.railLayer().segmentCount(), false));
    }

    /**
     * The segments of one machine-read rail layer, as a copy of the list.
     *
     * <p>The list is this call's own; the segments in it are not. They are the layer's current
     * geometry, rebuilt whenever the layer is, and they belong to the mod that read them: a caller
     * draws them and nothing else. Writing to one would corrupt the layer for every view that draws
     * it, which is why the layers are reported as not editable by {@link #railLayers()} and why the
     * mod's own write path refuses them. A caller that wants geometry it may change wants its own
     * roads, and the way to add one is the mod's editor or the write API.
     *
     * <p>A caller drawing them should ask again each pass rather than caching: Create's graph and
     * MTR's marks both rebuild on their own schedule, and a cached copy is a picture of where the
     * rails used to be.
     *
     * @param layerId one of {@link #LAYER_CREATE} or {@link #LAYER_MTR}; anything else is empty
     */
    public static List<RoadSegment> railLayerSegments(String layerId) {
        if (LAYER_CREATE.equals(layerId)) {
            return new ArrayList<>(RailTrackStore.segments());
        }
        if (LAYER_MTR.equals(layerId)) {
            return MtrTransit.railLayer().segmentsSnapshot();
        }
        return List.of();
    }
}
