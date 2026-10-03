package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.route.LinePlanner;
import bili.dongsz.howtogo.transit.LineStop;
import bili.dongsz.howtogo.transit.TransitLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * <h2>Why nothing is remembered between frames</h2>
 * The answer is worked out from the lines in play on every call. A remembered one would have to be
 * invalidated by every way a line can change -- deleted, renamed, dropped by MTR as the player walks
 * out of range, or rebuilt from a fresh reading -- and the way that gets forgotten is a marker left
 * standing for a line that is gone. The lines in play are a handful, and the grid below makes the pass
 * cheap enough to repeat: a stop is only compared against the stops in its own cell and the eight
 * around it, so the cost follows the number of stops rather than the square of it.
 */
final class TransitInterchanges {

    private TransitInterchanges() {
    }

    /**
     * The places where two lines meet, as the packed positions of every stop that shares its place with
     * a stop of another line.
     *
     * <p>Packed rather than returned as stops, because the caller is drawing a marker per stop and
     * already has the position: a set lookup per stop is what keeps this out of the drawing loop's way.
     */
    static Set<Long> shared(List<TransitLine> lines) {
        Set<Long> shared = new HashSet<>();
        if (lines == null || lines.isEmpty()) {
            return shared;
        }
        double radius = LinePlanner.transferRadius();
        double radiusSquared = radius * radius;
        int cell = Math.max(1, (int) Math.floor(radius));

        Map<Long, List<StopRef>> grid = new HashMap<>();
        for (TransitLine line : lines) {
            for (LineStop stop : line.stops()) {
                grid.computeIfAbsent(cellKey(stop.x(), stop.z(), cell), key -> new ArrayList<>())
                        .add(new StopRef(line.id(), stop.x(), stop.z()));
            }
        }

        for (List<StopRef> bucket : grid.values()) {
            for (StopRef here : bucket) {
                if (shared.contains(pack(here.x(), here.z()))) {
                    continue;
                }
                if (meetsAnotherLine(grid, here, cell, radiusSquared)) {
                    // Both ends of the pair are marked, since a change of lines is a place rather than a
                    // direction: whichever of the two the player is looking at is the interchange.
                    shared.add(pack(here.x(), here.z()));
                    markPartners(grid, here, cell, radiusSquared, shared);
                }
            }
        }
        return shared;
    }

    /** Whether any stop of another line stands within the radius of this one. */
    private static boolean meetsAnotherLine(Map<Long, List<StopRef>> grid, StopRef here, int cell,
                                            double radiusSquared) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                List<StopRef> bucket = grid.get(cellKey(here.x() + dx * cell, here.z() + dz * cell, cell));
                if (bucket == null) {
                    continue;
                }
                for (StopRef other : bucket) {
                    if (within(here, other, radiusSquared)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Marks every stop of another line that stands within the radius of this one. */
    private static void markPartners(Map<Long, List<StopRef>> grid, StopRef here, int cell,
                                     double radiusSquared, Set<Long> shared) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                List<StopRef> bucket = grid.get(cellKey(here.x() + dx * cell, here.z() + dz * cell, cell));
                if (bucket == null) {
                    continue;
                }
                for (StopRef other : bucket) {
                    if (within(here, other, radiusSquared)) {
                        shared.add(pack(other.x(), other.z()));
                    }
                }
            }
        }
    }

    /** Whether the two are close enough to change lines between, and are not the same line. */
    private static boolean within(StopRef here, StopRef other, double radiusSquared) {
        if (here.lineId().equals(other.lineId())) {
            return false;
        }
        double dx = here.x() - other.x();
        double dz = here.z() - other.z();
        return dx * dx + dz * dz <= radiusSquared;
    }

    /** One stop, with the line it belongs to: two stops of one line are not an interchange. */
    private record StopRef(String lineId, int x, int z) {
    }

    /** The grid cell a position falls in, by floor division so that negative coordinates behave. */
    private static long cellKey(int x, int z, int cell) {
        return ((long) Math.floorDiv(x, cell) << 32) | (Math.floorDiv(z, cell) & 0xFFFFFFFFL);
    }

    /** A position as one number, injectively: two positions pack alike only when they are equal. */
    static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
