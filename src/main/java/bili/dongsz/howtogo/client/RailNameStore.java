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
 * Names for the auto-detected rail layer, stored outside the layer.
 * The layer is rebuilt on every Create graph change and renumbers its segments, so a name cannot live on a segment id. It is keyed by the segment's two end points as whole blocks in canonical order -- a property of the track that survives rebuilds, re-joins and reloads. Two pieces sharing both ends share a name.
 * A rename names the whole chain via RoadChains; each segment of the chain gets its own entry, so a later cut keeps the name on both halves. The layer stays read-only apart from its display name; names go to their own file resolved by WorldFiles.
 */
public final class RailNameStore {

    public static final int FORMAT_VERSION = 1;

    /** Suffix distinguishing this file from the road network's, in the same world and dimension. */
    private static final String FILE_SUFFIX = "-rail-names";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** Name per segment shape key, for the currently loaded level. */
    private static Map<String, String> names = new HashMap<>();

    /**
     * The name each label-carrying segment should draw, recomputed on every rebuild.
     * One label per railway rather than per segment, so a chain does not draw its name a dozen times; computed once per rebuild to keep chain walking out of the render pass. Kept beside the id so a middle segment with no name still gets labelled.
     */
    private static Map<Integer, String> labels = Map.of();

    /**
     * Shape keys of the selected railway -- every segment of the chain, so the highlight covers the whole line. Keys are geometry, so the highlight survives a rebuild.
     */
    private static Set<String> selectedKeys = Set.of();

    /** Shape key the selection started from, needed when a rename is requested later. */
    private static String selectedSeed;

    private static Object boundLevel;
    private static Path boundPath;

    private RailNameStore() {
    }


    /**
     * Applies stored names to a freshly built layer and works out labels.
     * Called by RailTrackStore immediately after it builds, so the ids cannot be stale.
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
     * The name this segment should draw, or null when it does not label its railway. One map lookup per segment per frame.
     */
    public static String labelAt(RoadSegment segment) {
        return labels.get(segment.id());
    }

    /**
     * Names the whole railway the given segment belongs to, or clears it when name is null.
     * Addressed by shape key because the naming prompt answers a tick later and the layer may have been rebuilt; the key is found again in the current layer.
     * @param key the shape key the naming started from, as returned by keyOf
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


    /**
     * Marks the whole railway the segment belongs to as selected, for the highlight and a following rename.
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

    /** Shape key of the selected railway's seed segment, or null. */
    public static String selectedSeed() {
        return selectedSeed;
    }

    /**
     * The name stored for a shape key, or null.
     * Read from the store because a rebuild may have replaced the segment already.
     */
    public static String nameOfKey(String key) {
        return key == null ? null : names.get(key);
    }

    /** Whether this segment belongs to the selected railway. */
    public static boolean isSelected(RoadSegment segment) {
        return !selectedKeys.isEmpty() && selectedKeys.contains(keyOf(segment));
    }


    /**
     * A segment's identity: its two end points as whole blocks, in canonical order.
     * The polyline is not part of it: a curve resampled differently is the same track and must keep its name.
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
     * Each chain is walked once: per-segment walking would repeat RoadChains' continuation scan, quadratic in the size of the layer.
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


    /**
     * Re-reads the loaded dimension's names after the file was replaced.
     * Clearing labels here stops old names drawing at once rather than lingering until the next rebuild.
     */
    public static void reload() {
        ensureBound();
        labels = Map.of();
        names = boundPath == null ? new HashMap<>() : load(boundPath);
    }

    /**
     * Rebinds to the loaded level and reads its names; the selection is dropped because it names a shape in a layer that no longer exists.
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
            HowToGo.logInfo("[HowToGo] loaded {} rail name(s) from {}", result.size(),
                    file.getFileName());
        } catch (IOException | JsonSyntaxException e) {
            HowToGo.LOGGER.error("[HowToGo] could not read {}; starting with no rail names", file, e);
        }
        return result;
    }

    /** Written on every rename -- one deliberate action, not a stream of edits. */
    private static void save() {
        if (boundPath == null) {
            return;
        }
        try {
            Files.createDirectories(boundPath.getParent());
            Dto dto = new Dto();
            dto.version = FORMAT_VERSION;
            // Sorted, so an unchanged set writes identical bytes; map order is not stable between
            dto.names = new TreeMap<>(names);
            try (Writer writer = Files.newBufferedWriter(boundPath, StandardCharsets.UTF_8)) {
                GSON.toJson(dto, writer);
            }
            HowToGo.logInfo("[HowToGo] saved {} rail name(s) to {}", names.size(),
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
