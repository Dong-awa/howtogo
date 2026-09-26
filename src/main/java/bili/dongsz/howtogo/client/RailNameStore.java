package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadChains;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Names for the automatically detected rail layer, kept outside the layer itself.
 *
 * <h2>Why a name cannot live on a rail segment</h2>
 * The rail layer is rebuilt from scratch every time Create's graph changes, and each rebuild numbers
 * its segments from scratch -- {@code RailTrackStore} starts every build at its id base and counts up
 * in the order it happens to walk Create's edges. A segment id therefore means "the n-th edge of this
 * particular reading" and nothing more: lay one more piece of track and it can point at a different
 * edge. Storing a name against an id would have it appear on the wrong line, or vanish, with no
 * mistake by anyone.
 *
 * <p>So a name is stored against the segment's <b>shape</b> instead: its two end points, in a
 * canonical order, as whole blocks. That is a property of the track rather than of the reading, so it
 * survives rebuilds, re-joins, reloads and even a switch between the two sources of the layer. Two
 * rail pieces sharing both end points share a name, which for a straight line between the same two
 * junctions is the honest answer rather than a collision worth guarding against.
 *
 * <h2>Naming the line, not the piece</h2>
 * A railway line arrives as a chain of short segments through pass-through nodes, exactly the way a
 * bent road does. Naming one segment of it would leave the line called one thing for one stretch and
 * something else for the next, so a rename names the whole chain -- {@link RoadChains} already
 * decides what a whole road is, and this reuses that decision rather than inventing a second one.
 * Each segment of the chain gets its own entry, so if the network is later cut by a junction the two
 * halves keep the name instead of losing it.
 *
 * <h2>The layer stays read-only</h2>
 * This store is the only place a rail name is written, and the only thing it ever does to the layer
 * is set a display name on a segment that was just built. The saved road network is untouched: rail
 * names go to their own file beside it, resolved by the same {@link WorldFiles} as the roads so the
 * two can never disagree about which world and dimension they describe.
 */
public final class RailNameStore {

    public static final int FORMAT_VERSION = 1;

    /** Distinguishes this file from the road network's, in the same world and dimension. */
    private static final String FILE_SUFFIX = "-rail-names";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** Name per segment shape key, for the level currently loaded. */
    private static Map<String, String> names = new HashMap<>();

    /**
     * The name each label-carrying segment should draw, recomputed on every rebuild of the layer.
     *
     * <p>One label per railway rather than one per segment: a chain of a dozen pieces would
     * otherwise draw its name a dozen times along the same line. Computed here, once per rebuild,
     * because the alternative is walking every chain during the render pass of every frame.
     *
     * <p>The name is stored beside the id rather than read back off the segment, so a chain whose
     * middle piece happens to carry no name still gets labelled instead of silently losing its name
     * from the map.
     */
    private static Map<Integer, String> labels = Map.of();

    /**
     * The shape keys of the railway the player has selected.
     *
     * <p>Every segment of the chain, not just the one that was pointed at: a railway is one line to
     * the player, and highlighting only the piece under the cursor makes a selected line look like a
     * stray fragment of one. The keys are geometry, so the highlight survives a rebuild without
     * anything having to be recomputed.
     */
    private static Set<String> selectedKeys = Set.of();

    /**
     * The shape key the selection started from, for a rename that is asked for later.
     *
     * <p>Kept beside the set because a rename has to be addressed to one segment of the chain in
     * order to find the chain again, and a set has no first element that means anything.
     */
    private static String selectedSeed;

    private static Object boundLevel;
    private static Path boundPath;

    private RailNameStore() {
    }

    // ------------------------------------------------------------------ layer

    /**
     * Applies the stored names to a freshly built layer, and works out which segments label it.
     *
     * <p>Called by {@link RailTrackStore} immediately after it builds, so the ids this reads are the
     * ones of the network it was just handed and cannot be a rebuild out of date.
     */
    public static void apply(RoadNetwork rail) {
        ensureBound();
        for (RoadSegment segment : rail.segmentsSnapshot()) {
            String name = names.get(keyOf(segment));
            if (name != null) {
                segment.setName(name);
            }
        }
        recomputeLabels(rail);
    }

    /**
     * The name this segment should draw, or null when it is not the one labelling its railway.
     *
     * <p>Asked once per rail segment per frame, so it is a map lookup and nothing more.
     */
    public static String labelAt(RoadSegment segment) {
        return labels.get(segment.id());
    }

    /**
     * Names the whole railway the given segment belongs to, or clears its name when given nothing.
     *
     * <p>Addressed by shape key rather than by id on purpose: the naming prompt is answered a tick
     * later, and the layer is rebuilt on its own schedule, so by the time a name arrives the id it
     * started from may belong to a different segment. The key is found again in whatever the layer is
     * now.
     *
     * @param key the shape key the naming started from, as returned by {@link #keyOf}
     */
    public static void rename(RoadNetwork rail, String key, String name) {
        ensureBound();
        int seed = segmentWithKey(rail, key);
        if (seed == RoadSegment.NO_SEGMENT) {
            return;
        }
        String wanted = name == null ? "" : name.trim();
        List<Integer> chain = RoadChains.chainContaining(rail, seed);
        if (chain.isEmpty()) {
            chain = List.of(seed);
        }
        Set<String> renamed = new HashSet<>();
        for (int id : chain) {
            RoadSegment segment = rail.segment(id);
            if (segment == null) {
                continue;
            }
            String segmentKey = keyOf(segment);
            renamed.add(segmentKey);
            if (wanted.isEmpty()) {
                names.remove(segmentKey);
                segment.setName(null);
            } else {
                names.put(segmentKey, wanted);
                segment.setName(wanted);
            }
        }
        selectedKeys = wanted.isEmpty() ? Set.of() : renamed;
        selectedSeed = wanted.isEmpty() ? null : key;
        recomputeLabels(rail);
        save();
    }

    // -------------------------------------------------------------- selection

    /**
     * Marks the whole railway the given segment belongs to as selected, for the highlight and for a
     * following rename.
     *
     * <p>The whole chain, so the highlight covers the line the player believes they selected.
     */
    public static void select(RoadNetwork rail, RoadSegment segment) {
        if (segment == null) {
            clearSelection();
            return;
        }
        selectedSeed = keyOf(segment);
        List<Integer> chain = RoadChains.chainContaining(rail, segment.id());
        if (chain.isEmpty()) {
            selectedKeys = Set.of(selectedSeed);
            return;
        }
        Set<String> keys = new HashSet<>();
        for (int id : chain) {
            RoadSegment member = rail.segment(id);
            if (member != null) {
                keys.add(keyOf(member));
            }
        }
        selectedKeys = keys;
    }

    public static void clearSelection() {
        selectedKeys = Set.of();
        selectedSeed = null;
    }

    /** The shape key of the selected railway's seed segment, or null when nothing is selected. */
    public static String selectedSeed() {
        return selectedSeed;
    }

    /**
     * The name stored for a shape key, or null.
     *
     * <p>Read from the store rather than from a segment, because the caller that opens the naming
     * prompt may be holding a key whose segment has already been replaced by a rebuild.
     */
    public static String nameOfKey(String key) {
        return key == null ? null : names.get(key);
    }

    /** Whether this segment belongs to the selected railway. */
    public static boolean isSelected(RoadSegment segment) {
        return !selectedKeys.isEmpty() && selectedKeys.contains(keyOf(segment));
    }

    // ------------------------------------------------------------------ shape

    /**
     * A segment's identity: its two end points, as whole blocks, in a canonical order.
     *
     * <p>The whole polyline is not part of it. A curve resampled slightly differently by a new
     * reading of Create's graph is the same piece of track, and it has to keep its name; its ends are
     * what the topology actually fixes.
     */
    public static String keyOf(RoadSegment segment) {
        int last = segment.vertexCount() - 1;
        int x0 = segment.x(0);
        int z0 = segment.z(0);
        int x1 = segment.x(last);
        int z1 = segment.z(last);
        if (x1 < x0 || (x1 == x0 && z1 < z0)) {
            int swap = x0;
            x0 = x1;
            x1 = swap;
            swap = z0;
            z0 = z1;
            z1 = swap;
        }
        return x0 + "," + z0 + ">" + x1 + "," + z1;
    }

    private static int segmentWithKey(RoadNetwork rail, String key) {
        if (key == null) {
            return RoadSegment.NO_SEGMENT;
        }
        for (RoadSegment segment : rail.segmentsSnapshot()) {
            if (key.equals(keyOf(segment))) {
                return segment.id();
            }
        }
        return RoadSegment.NO_SEGMENT;
    }

    /**
     * One label per named railway, at the middle of its chain.
     *
     * <h2>Why each chain is walked once</h2>
     * Every segment of a named chain carries the name, so a naive pass would walk the same chain
     * once per segment -- and {@link RoadChains} finds the continuation through a node by scanning
     * the whole network, which makes that quadratic in the size of the layer on top of being
     * redundant. A chain that has been walked is remembered here, so the cost is one walk per
     * railway rather than one per piece of it.
     */
    private static void recomputeLabels(RoadNetwork rail) {
        if (names.isEmpty()) {
            labels = Map.of();
            return;
        }
        Map<Integer, String> found = new HashMap<>();
        Set<Integer> walked = new HashSet<>();
        for (RoadSegment segment : rail.segmentsSnapshot()) {
            if (walked.contains(segment.id())) {
                continue;
            }
            String own = segment.name();
            if (own == null || own.isBlank()) {
                continue;
            }
            List<Integer> chain = RoadChains.chainContaining(rail, segment.id());
            if (chain.isEmpty()) {
                continue;
            }
            walked.addAll(chain);
            int middle = RoadChains.middleSegment(chain);
            if (middle != RoadSegment.NO_SEGMENT) {
                found.put(middle, own);
            }
        }
        labels = found;
    }

    // ------------------------------------------------------------ persistence

    /**
     * Rebinds to the level now loaded, reading that level's names.
     *
     * <p>The selection is dropped on any rebind: it names a shape in a layer that no longer exists.
     */
    private static void ensureBound() {
        Object level = Minecraft.getInstance().level;
        if (level == boundLevel && (boundPath != null || level == null)) {
            return;
        }
        boundLevel = level;
        selectedKeys = Set.of();
        selectedSeed = null;
        labels = Map.of();
        if (level == null) {
            boundPath = null;
            names = new HashMap<>();
            return;
        }
        boundPath = WorldFiles.of(FILE_SUFFIX);
        names = load(boundPath);
    }

    private static Map<String, String> load(Path file) {
        Map<String, String> result = new HashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return result;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Dto dto = GSON.fromJson(reader, Dto.class);
            if (dto != null && dto.names != null) {
                for (Map.Entry<String, String> entry : dto.names.entrySet()) {
                    String value = entry.getValue() == null ? "" : entry.getValue().trim();
                    if (entry.getKey() != null && !value.isEmpty()) {
                        result.put(entry.getKey(), value);
                    }
                }
            }
            HowToGo.LOGGER.info("[HowToGo] loaded {} rail name(s) from {}", result.size(),
                    file.getFileName());
        } catch (IOException | JsonSyntaxException e) {
            HowToGo.LOGGER.error("[HowToGo] could not read {}; starting with no rail names", file, e);
        }
        return result;
    }

    /** Written on every rename, which is one deliberate action rather than a stream of edits. */
    private static void save() {
        if (boundPath == null) {
            return;
        }
        try {
            Files.createDirectories(boundPath.getParent());
            Dto dto = new Dto();
            dto.version = FORMAT_VERSION;
            // Sorted, so an unchanged set of names writes identical bytes: a map's own order is not
            // stable between runs, and churn in a file that did not change is a false alarm.
            dto.names = new TreeMap<>(names);
            try (Writer writer = Files.newBufferedWriter(boundPath, StandardCharsets.UTF_8)) {
                GSON.toJson(dto, writer);
            }
            HowToGo.LOGGER.info("[HowToGo] saved {} rail name(s) to {}", names.size(),
                    boundPath.getFileName());
        } catch (IOException e) {
            HowToGo.LOGGER.error("[HowToGo] could not write {}", boundPath, e);
        }
    }

    private static final class Dto {
        int version;
        Map<String, String> names;
    }
}
