package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.client.AddonRoadStore;
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
 * What the mod currently knows about the world the player is in: roads, lines, stations and the machine-read rail layers.
 * Snapshots, not live objects: every accessor copies, because the stores replace what they hold on a dimension or server change, and a caller mutating what it holds would change state about to be written to disk. The cost is a copy per call, so these are queries, not per-frame drawing paths.
 * Client thread only: the machine-read layers rebuild on their own schedule and the MTR reader keeps a worker, so calling elsewhere could read a half-built layer.
 * Read-only is a fact: Create's and MTR's rails and lines belong to those mods, so railLayers() reports them not editable; roadsSnapshot() is the only editable thing here.
 */
public final class WorldDataApi {

    /** Id of Create's rail layer in railLayers() and railLayerSegments(String). */
    public static final String LAYER_CREATE = "create";

    /** Id of MTR's marked-tracks layer in railLayers() and railLayerSegments(String). */
    public static final String LAYER_MTR = "mtr";

    private WorldDataApi() {
    }

    /**
     * The player's road network for the dimension in play, as a copy. Editing the copy changes nothing; the write path is the mod's editor or the write API.
     */
    public static RoadNetwork roadsSnapshot() {
        return RoadStore.get().deepCopy();
    }

    /**
     * The road network's revision, which changes whenever it does -- for a caller that keeps something derived and wants to know whether it is still current. Compare it with the value read beside the data, not across a level change.
     */
    public static int roadRevision() {
        return RoadStore.get().revision();
    }

    /**
     * The player's public transport lines for the dimension in play, as copies.
     * Lines read out of MTR are not here: they are MTR's, rebuilt from what MTR sends, and offered as destinations and for planning without ever being saved with the player's own.
     * This is the list the mod's line editor edits.
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
     * Every station the player could board at, from all three sources. Unnamed places are left out -- a station with no name cannot be selected, searched for or announced. Order is stable: the player's stations, then Create's, then MTR's.
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
     * The revision of the addon layers, bumped whenever one of them is re-read -- the same contract as roadRevision(), and a world change moves it because a layer belongs to one dimension.
     */
    public static int addonRevision() {
        return AddonRoadStore.revision();
    }

    /**
     * The ids of the addon layers registered with this mod, in registration order. An id is a layer some mod states, not a promise that it has anything in the current dimension.
     */
    public static List<String> addonLayerIds() {
        return RoadDataSources.ids();
    }

    /**
     * The machine-read rail layers, described. Entries are present whether or not the owning mod is installed; a layer with nothing in it has a segment count of zero.
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
     * The list is this call's own; the segments are not -- they are the layer's current geometry and belong to the mod that read them. A caller draws them and nothing else, since writing would corrupt the layer for every view; the layers are reported not editable.
     * A caller should ask again each pass rather than caching: Create's graph and MTR's marks rebuild on their own schedule. layerId is one of LAYER_CREATE or LAYER_MTR; anything else is empty.
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
