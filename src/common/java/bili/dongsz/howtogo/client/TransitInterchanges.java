package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.route.LinePlanner;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which stops a journey may change lines at, for the orange marker on the map.
 *
 * <h2>Two lines, standing close enough to walk between</h2>
 * A place is an interchange when a stop of one line and a stop of <em>another</em> line stand within
 * {@link LinePlanner#transferRadius()} of each other -- the very rule the planner builds its transfers
 * from. It is deliberately that rule and not one of the map's own: a map that marked a different set of
 * places from the ones a journey may change lines at would be the map arguing with the route.
 *
 * <p>Both requirements matter, and each was once wrong here. Measuring by exact position missed the
 * interchange that is the commonest one there is -- a rail platform and the stop beside it, two places
 * in the data and one place to travel through -- so an MTR line and a bus line meeting at one station
 * showed as two separate stops. And counting stops instead of lines marked a place orange for the stops
 * of a single line that happened to stand near each other, which is not a change of lines at all, and
 * which no amount of cancelling lines would clear, because no second line was ever involved.
 *
 * <h2>Why the answer is remembered, and what invalidates it</h2>
 * This used to be worked out afresh on every call, on the grounds that a remembered answer would have
 * to be invalidated by every way a line can change -- deleted, renamed, dropped by MTR as the player
 * walks out of range, or rebuilt from a fresh reading -- and the way that gets forgotten is a marker
 * left standing for a line that is gone. That reasoning is right, and it is what the signature below
 * is for: the answer stands while the lines are the same lines calling at the same stops in the same
 * order, and the signature is built from exactly those things, so every one of those changes changes
 * it. What the memory buys is the frame: the map asks this once per frame with every stop of every
 * line in play, and the pass below asks every stop about every other.
 *
 * <p>The pass is not the grid this comment used to promise. It compares each stop against all of them,
 * which is the square of the stop count -- tolerable once when the lines change, and not tolerable
 * sixty times a second, which is the difference the signature makes.
 */
final class TransitInterchanges {

    private TransitInterchanges() {
    }

    /**
     * The places where two lines meet, each with the stops that make it up and where it is drawn.
     *
     * <p>Grouped rather than returned stop by stop, because a place two lines meet at is one place: the
     * caller draws one marker for the group, at the middle of it, and leaves the stops it holds out of
     * the ordinary markers. Two markers a few blocks apart that overlap on screen say "two stations",
     * which is the opposite of what the rule found.
     *
     * <p>The groups are transitive: stops near enough to be walked between are one place however many
     * lines call there, which is what makes a three-line interchange one marker rather than two pairs.
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

    /**
     * A number standing for the lines and the stops they call at, in order.
     *
     * <p>Deliberately not a count of anything: a line renamed, or one stop replaced by another at the
     * same place, would leave a count unchanged and a stale marker standing. Hashed rather than kept as
     * a string because it is built once per frame over every stop; where the hash collides, the cost is
     * one frame of a marker that has not moved yet.
     */
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
            // Grown until it stops growing: a stop joins when it is within the radius of one already in,
            // which is the same walk a player makes between platforms.
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
     * Whether the stops of one group belong to two or more lines.
     *
     * <p>The whole point of the rule: a marker for one line's own stops standing close together is not an
     * interchange, and marking it as one leaves a marker that no amount of cancelling lines will clear.
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
     * A place two or more lines meet at, as the stops that make it up.
     *
     * @param stops   the stops of every line calling here, each as {@code {x, z}}
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

        /** Where the place's stops are on the whole, which is where its marker goes. */
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
     *
     * <h2>Why the merging is decided here and not on the ground</h2>
     * Whether two markers overlap is a question about the screen, not about the world: the same two
     * stations are one blob when the map is zoomed out and two clear dots when it is zoomed in, and a
     * marker that stayed fused at every zoom would be the map refusing to show what it knows. So the
     * place is found in world blocks -- which stops are near enough to walk between -- and the markers
     * are drawn from where those stops land.
     *
     * <p>Grouped transitively, as the place itself is: three stops in a row are one marker when each
     * touches the next, whatever the two ends are to each other.
     *
     * @param screenX       world x to screen x, in pixels
     * @param screenZ       world z to screen z, in pixels
     * @param mergeDistance how close two markers have to be to become one, in pixels
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
