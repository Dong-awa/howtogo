package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.route.LinePlanner;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which stops a journey may change lines at, for the orange map marker.
 * A place is an interchange when a stop of one line and another line's stop lie within
 * LinePlanner#transferRadius -- the planner's own rule. Grouping is transitive.
 *
 * The signature covers lines and ordered stops, so any change invalidates the cache; the all-pairs
 * pass is O(n^2), acceptable when lines change, not per frame.
 */
final class TransitInterchanges {

    private TransitInterchanges() {
    }

    /**
     * The places where two lines meet, each with its stops and where it is drawn.
     * Grouped because one meeting place is one place: one marker at the middle, its stops left out of
     * ordinary markers.
     */
    static List<Interchange> of(List<TransitLine> lines) {
        long signature = signatureOf(lines);
        if (cached != null && signature == cachedSignature) {
            return cached;
        }
        List<Interchange> found = compute(lines);
        cached = List.copyOf(found);
        cachedSignature = signature;
        return cached;
    }

    /** The last answer, with the reading of the lines it was worked out from. */
    private static long cachedSignature = Long.MIN_VALUE;
    private static List<Interchange> cached;

    /** A hash of the lines and their ordered stops; a renamed line or swapped stop changes it. */
    private static long signatureOf(List<TransitLine> lines) {
        long hash = 1125899906842597L;
        if (lines == null) {
            return hash;
        }
        for (TransitLine line : lines) {
            hash = hash * 31 + line.id().hashCode();
            hash = hash * 31 + line.kind().ordinal();
            hash = hash * 31 + line.stopCount();
            for (LineStop stop : line.stops()) {
                hash = hash * 31 + stop.x();
                hash = hash * 31 + stop.z();
            }
        }
        return hash;
    }

    private static List<Interchange> compute(List<TransitLine> lines) {
        List<StopRef> stops = new ArrayList<>();
        if (lines != null) {
            for (TransitLine line : lines) {
                for (LineStop stop : line.stops()) {
                    stops.add(new StopRef(line.id(), stop.x(), stop.z()));
                }
            }
        }
        List<Interchange> found = new ArrayList<>();
        boolean[] grouped = new boolean[stops.size()];
        for (int i = 0; i < stops.size(); i++) {
            if (grouped[i]) {
                continue;
            }
            List<StopRef> group = new ArrayList<>();
            group.add(stops.get(i));
            grouped[i] = true;
            // Grown until it stops growing: a stop joins when within the radius of one already in.
            for (int at = 0; at < group.size(); at++) {
                StopRef member = group.get(at);
                for (int j = 0; j < stops.size(); j++) {
                    if (!grouped[j] && near(member, stops.get(j))) {
                        grouped[j] = true;
                        group.add(stops.get(j));
                    }
                }
            }
            if (callsTwoLines(group)) {
                found.add(interchangeOf(group));
            }
        }
        return found;
    }

    /**
     * Whether a group's stops belong to two or more lines; one line's own nearby stops are no interchange.
     */
    private static boolean callsTwoLines(List<StopRef> group) {
        String first = group.get(0).lineId();
        for (StopRef stop : group) {
            if (!stop.lineId().equals(first)) {
                return true;
            }
        }
        return false;
    }

    /** One group as a place: the stops that make it up, and which lines call there. */
    private static Interchange interchangeOf(List<StopRef> group) {
        List<int[]> stops = new ArrayList<>(group.size());
        Set<String> lineIds = new HashSet<>();
        for (StopRef stop : group) {
            stops.add(new int[] {stop.x(), stop.z()});
            lineIds.add(stop.lineId());
        }
        return new Interchange(List.copyOf(stops), lineIds);
    }

    /** Whether two stops are close enough to change lines between, and are not the same line. */
    private static boolean near(StopRef here, StopRef other) {
        if (here.lineId().equals(other.lineId())) {
            return false;
        }
        double radius = LinePlanner.transferRadius();
        double dx = here.x() - other.x();
        double dz = here.z() - other.z();
        return dx * dx + dz * dz <= radius * radius;
    }

    /**
     * A place two or more lines meet at.
     * @param stops the stops of every line calling here, each as {x, z}
     * @param lineIds the lines that call there, two or more by construction
     */
    record Interchange(List<int[]> stops, Set<String> lineIds) {

        /** Whether the given position is one of the stops in this place. */
        boolean holds(int x, int z) {
            for (int[] stop : stops) {
                if (stop[0] == x && stop[1] == z) {
                    return true;
                }
            }
            return false;
        }

        /** Where the place's stops are on average, which is where its marker goes. */
        int centreX() {
            return (int) Math.round(average(0));
        }

        /** The same for z. */
        int centreZ() {
            return (int) Math.round(average(1));
        }

        private double average(int axis) {
            double sum = 0;
            for (int[] stop : stops) {
                sum += stop[axis];
            }
            return stops.isEmpty() ? 0 : sum / stops.size();
        }
    }

    /**
     * The stops of one place that land on top of each other on screen, as groups of indices.
     * Overlap is a screen question: the same stops merge when zoomed out and separate when zoomed in,
     * so places are found in world blocks and markers are drawn from where those stops land. Grouped
     * transitively.
     * @param screenX world x to screen x, in pixels
     * @param screenZ world z to screen z, in pixels
     * @param mergeDistance how close two markers must be to merge, in pixels
     */
    static List<List<Integer>> overlapping(Interchange interchange,
                                           java.util.function.IntUnaryOperator screenX,
                                           java.util.function.IntUnaryOperator screenZ,
                                           double mergeDistance) {
        List<int[]> stops = interchange.stops();
        boolean[] grouped = new boolean[stops.size()];
        List<List<Integer>> groups = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            if (grouped[i]) {
                continue;
            }
            List<Integer> group = new ArrayList<>();
            group.add(i);
            grouped[i] = true;
            for (int at = 0; at < group.size(); at++) {
                int[] here = stops.get(group.get(at));
                for (int j = 0; j < stops.size(); j++) {
                    if (grouped[j]) {
                        continue;
                    }
                    int[] other = stops.get(j);
                    double dx = screenX.applyAsInt(here[0]) - screenX.applyAsInt(other[0]);
                    double dz = screenZ.applyAsInt(here[1]) - screenZ.applyAsInt(other[1]);
                    if (dx * dx + dz * dz <= mergeDistance * mergeDistance) {
                        grouped[j] = true;
                        group.add(j);
                    }
                }
            }
            groups.add(group);
        }
        return groups;
    }

    /** One stop, with the line it belongs to: two stops of one line are not an interchange. */
    private record StopRef(String lineId, int x, int z) {
    }
}
