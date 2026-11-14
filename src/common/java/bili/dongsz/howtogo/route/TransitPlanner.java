package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.List;

/**
 * The front door of public transport routing: a journey over the player's lines, or nothing.
 * No fallback to a station-pair search. That used to board at the nearest track, ride it as far as it went and often not reach the destination, which reads to the player as the mod ignoring their lines. If no line can carry the journey, the honest answer is none, and the walking comparison upstream can offer the walk instead.
 * LinePlanner holds the routing: stops are nodes of a directed weighted graph, links between neighbouring stops on a line are its edges, Dijkstra is the search. This class only finds the player's own stations, which the line editor offers as candidates.
 */
public final class TransitPlanner {

    private TransitPlanner() {
    }

    /**
     * Plans a journey over the lines, or reports that there is none.
     * @return the journey, or Trip empty() when no line can carry it
     */
    public static Trip plan(RoadNetwork network, List<TransitLine> lines, double startX, double startZ,
                            double goalX, double goalZ, String destinationName,
                            RoutePreferences preferences) {
        return plan(RideRoads.of(network), lines, startX, startZ, goalX, goalZ, destinationName,
                preferences);
    }

    /** Plans over roads that depend on the line, which is how a line's own marks are switched off. */
    public static Trip plan(RideRoads roads, List<TransitLine> lines, double startX, double startZ,
                            double goalX, double goalZ, String destinationName,
                            RoutePreferences preferences) {
        if (lines.isEmpty()) {
            HowToGo.LOGGER.info("[HowToGo] public transport: no lines configured");
            return Trip.empty();
        }
        return LinePlanner.plan(roads, lines, startX, startZ, goalX, goalZ, destinationName,
                preferences);
    }

    /**
     * The journey as one route, for the map, the readout and the estimate. See asRoute.
     * @return the journey, or Route empty() when no line can carry it
     */
    public static Route planRoute(RoadNetwork network, List<TransitLine> lines, double startX,
                                  double startZ, double goalX, double goalZ, String destinationName,
                                  RoutePreferences preferences) {
        return planRoute(RideRoads.of(network), lines, startX, startZ, goalX, goalZ, destinationName,
                preferences);
    }

    /** The same, over roads that depend on the line. See RideRoads. */
    public static Route planRoute(RideRoads roads, List<TransitLine> lines, double startX,
                                  double startZ, double goalX, double goalZ, String destinationName,
                                  RoutePreferences preferences) {
        return asRoute(plan(roads, lines, startX, startZ, goalX, goalZ, destinationName, preferences),
                destinationName);
    }

    /**
     * The whole journey as one route, for the map, the readout and the estimate.
     * One route rather than a chain, because everything downstream understands routes: the map draws points(), the readout measures along it, the estimate sums it. Per-piece paces keep a walked stretch timed as a walk and a ridden one as a ride.
     * Flattening loses which line is in force at a given moment, so the panel says public transport while the player walks to a stop. Boarding, alighting and interchange points survive as distances in Trip ride().
     * Public so the navigation can plan the journey itself and flatten it here instead of flattening twice.
     */
    public static Route asRoute(Trip trip, String destinationName) {
        if (!trip.isPresent()) {
            return Route.empty();
        }
        List<Route> parts = new ArrayList<>(trip.legs().size());
        for (Trip.Leg leg : trip.legs()) {
            parts.add(leg.route());
            // One line per leg, with its length: a detouring walk and a looping ride look identical from the journey total.
            HowToGo.diagnostic("[HowToGo] public transport leg: {} {} blocks", leg.mode().id(),
                    Math.round(leg.route().totalLength()));
        }
        return Route.concat(parts, TravelMode.TRANSIT, destinationName);
    }

    /**
     * The player's own stations: place nodes marked as stations, in no particular order.
     * Stations Create's track graph reports are ordinary rail vertices, not place nodes, so no search over the road network could pick them out; TransitStops joins the two.
     */
    public static List<LineStop> markedStations(RoadNetwork network) {
        List<LineStop> result = new ArrayList<>();
        for (RoadNode node : network.nodes()) {
            if (node.type() == RoadNode.Type.POI && node.placeKind() == PlaceKind.STATION) {
                result.add(LineStop.ofPlace(node.id(), node.name(), node.x(), node.z()));
            }
        }
        return result;
    }
}
