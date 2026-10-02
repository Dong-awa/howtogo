package bili.dongsz.howtogo.route;

import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Plans a journey by public transport as legs: walk to a station, ride, walk to the destination.
 *
 * <h2>Why this cannot be one route</h2>
 * A single-mode route has one pace for the whole path, so it cannot say that the first part is walked
 * and the middle part is ridden -- and more to the point it cannot say <em>where</em> the riding
 * begins. That is the requirement: the riding leg must start and end at stations, not at whatever
 * point of the line the walk happened to reach. Composing the journey from three routes makes both
 * facts structural: the middle leg is planned from one station's coordinates to another's, so its
 * ends are stations by construction.
 *
 * <h2>Why the walk is planned rather than assumed</h2>
 * The connector hop at either end of any trip is already walked, but it is a straight line capped at
 * {@link TravelMode#maxConnectorDistance()} blocks. Walking a real route to a station instead means
 * the cap applies where it belongs -- to how far the player is from the road network, not to how far
 * the station is from the player.
 *
 * <h2>Cost</h2>
 * Up to {@link #CANDIDATES_PER_END} stations are tried at each end, so at most nine station pairs and
 * twenty-seven route plans. That is a deliberate bound: a network with fifty stations would otherwise
 * plan thousands of routes for one press of a button. The nearest stations are the ones a person would
 * actually consider, so the bound costs very little in practice.
 */
public final class TransitPlanner {

    /**
     * How many stations at each end are tried.
     *
     * <p>Three, because the nearest station is usually right and the second-nearest sometimes is: it
     * may sit on a faster line, or the nearest may be on a line that does not reach the other end at
     * all. Beyond a few, the extra plans are almost always slower and are paid for every time.
     */
    private static final int CANDIDATES_PER_END = 3;

    private TransitPlanner() {
    }

    /**
     * A place a journey may be boarded or left at: a name and where it is.
     *
     * <p>Deliberately neither a road node nor a {@link Destination}. The stations a journey may use
     * come from two unrelated sources -- places the player marked, which <em>are</em> road nodes, and
     * the ones Create's track graph reports, which are ordinary rail vertices and not places at all --
     * so a type able to hold only one of them would force the other to be faked into something it is
     * not. A blank name becomes the position, so the readout can always name where the riding starts.
     */
    public record Stop(String name, int x, int z) {
        public Stop {
            if (name == null || name.isBlank()) {
                name = "(" + x + ", " + z + ")";
            }
        }
    }

    /**
     * Plans the best ride-and-walk trip between two points.
     *
     * <p>Best by total time across the whole journey, not by any one leg: a station slightly further
     * away on a much faster line wins, which is the choice a person makes.
     *
     * @return the trip, or {@link Trip#empty()} when no station pair yields a complete journey
     */
    public static Trip plan(RoadNetwork network, List<Stop> stops, double startX, double startZ,
                            double goalX, double goalZ, String destinationName,
                            RoutePreferences preferences) {
        // The player's own stations and Create's arrive as two lists that overlap whenever a station
        // was both marked by hand and built, and a duplicate would spend one of the three candidate
        // slots at that end planning the same journey twice.
        List<Stop> unique = distinct(stops);
        List<Stop> boarding = nearestStations(unique, startX, startZ);
        List<Stop> alighting = nearestStations(unique, goalX, goalZ);
        if (boarding.isEmpty() || alighting.isEmpty()) {
            return Trip.empty();
        }

        Trip best = Trip.empty();
        double bestSeconds = Double.MAX_VALUE;
        for (Stop from : boarding) {
            for (Stop to : alighting) {
                if (from.x() == to.x() && from.z() == to.z()) {
                    // Boarding and alighting at one station is not a journey, it is a walk to a station
                    // and back, and it would always lose on time anyway.
                    continue;
                }
                Trip candidate = via(network, startX, startZ, goalX, goalZ, destinationName,
                        preferences, from, to);
                if (candidate == null) {
                    continue;
                }
                double seconds = candidate.estimatedSeconds();
                if (seconds < bestSeconds) {
                    bestSeconds = seconds;
                    best = candidate;
                }
            }
        }
        return best;
    }

    /**
     * The whole journey as one route, for the map, the readout and the estimate.
     *
     * <p>One route rather than three because that is what everything downstream understands: the map
     * draws {@code points()}, the readout measures along it, the estimate sums it. Each leg's own pace
     * travels with it inside the route's per-piece paces, so a walked stretch is timed as a walk and a
     * ridden one as a ride without any of those callers knowing there was more than one mode involved.
     *
     * <p>What is lost by flattening is the ability to say <em>which</em> mode is in force at a given
     * moment -- a route carries one mode, so the panel will say "public transport" while the player is
     * walking to the station. The boarding and alighting points are unaffected: they were fixed when
     * the legs were planned, from station coordinates.
     *
     * @return the journey, or {@link Route#empty()} when no station pair yields a complete trip
     */
    public static Route planRoute(RoadNetwork network, List<Stop> stops, double startX, double startZ,
                                  double goalX, double goalZ, String destinationName,
                                  RoutePreferences preferences) {
        Trip trip = plan(network, stops, startX, startZ, goalX, goalZ, destinationName, preferences);
        if (!trip.isPresent()) {
            return Route.empty();
        }
        List<Route> parts = new ArrayList<>(trip.legs().size());
        for (Trip.Leg leg : trip.legs()) {
            parts.add(leg.route());
        }
        return Route.concat(parts, TravelMode.TRANSIT, destinationName);
    }

    private static Trip via(RoadNetwork network, double startX, double startZ, double goalX,
                            double goalZ, String destinationName, RoutePreferences preferences,
                            Stop from, Stop to) {
        Route walkToStation = RoadRouter.findRoute(network, startX, startZ, from.x(), from.z(),
                destinationName, TravelMode.WALK, preferences);
        if (!walkToStation.isPresent()) {
            return null;
        }
        Route ride = RoadRouter.findRoute(network, from.x(), from.z(), to.x(), to.z(),
                destinationName, TravelMode.TRANSIT, preferences);
        if (!ride.isPresent()) {
            return null;
        }
        Route walkFromStation = RoadRouter.findRoute(network, to.x(), to.z(), goalX, goalZ,
                destinationName, TravelMode.WALK, preferences);
        if (!walkFromStation.isPresent()) {
            return null;
        }
        return Trip.of(List.of(
                        new Trip.Leg(walkToStation, TravelMode.WALK),
                        new Trip.Leg(ride, TravelMode.TRANSIT),
                        new Trip.Leg(walkFromStation, TravelMode.WALK)),
                from.name(), to.name());
    }

    /**
     * The player's own stations: place nodes marked as stations, in no particular order.
     *
     * <p>Only half of what a journey may board, and the smaller half in most worlds. The stations
     * Create's track graph reports are not place nodes at all -- they arrive as ordinary rail vertices
     * -- so walking the road network can never find them; the caller adds those, because the caller is
     * the side that can see the track layer. A road vertex cannot be a station here, because whether a
     * node is a place at all is its type.
     */
    public static List<Stop> markedStations(RoadNetwork network) {
        List<Stop> result = new ArrayList<>();
        for (RoadNode node : network.nodes()) {
            if (node.type() == RoadNode.Type.POI && node.placeKind() == PlaceKind.STATION) {
                result.add(new Stop(node.name(), node.x(), node.z()));
            }
        }
        return result;
    }

    /** The given stations with duplicates removed, so that one station holds one candidate slot. */
    private static List<Stop> distinct(List<Stop> stops) {
        List<Stop> result = new ArrayList<>(stops.size());
        for (Stop stop : stops) {
            boolean seen = false;
            for (Stop kept : result) {
                if (kept.x() == stop.x() && kept.z() == stop.z()) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                result.add(stop);
            }
        }
        return result;
    }

    /** The nearest {@link #CANDIDATES_PER_END} stations to a point, nearest first. */
    private static List<Stop> nearestStations(List<Stop> stations, double x, double z) {
        if (stations.isEmpty()) {
            return List.of();
        }
        List<Stop> sorted = new ArrayList<>(stations);
        sorted.sort(Comparator.comparingDouble(stop -> {
            double dx = stop.x() - x;
            double dz = stop.z() - z;
            return dx * dx + dz * dz;
        }));
        return sorted.subList(0, Math.min(CANDIDATES_PER_END, sorted.size()));
    }
}
