package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.api.DestinationSources;
import bili.dongsz.howtogo.place.PlaceDetails;
import bili.dongsz.howtogo.place.PlaceKey;
import bili.dongsz.howtogo.place.PlaceKeys;
import bili.dongsz.howtogo.place.PlaceSources;
import bili.dongsz.howtogo.place.PlaceStorage;
import bili.dongsz.howtogo.road.RoadNode;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.DestinationSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Client-side holder for the place-detail records of the loaded world.
 * One record type keyed by PlaceKey covers every source, because MTR stations, Xaero waypoints and
 * RoadWeaver structures are not road nodes: a second path would mean two sets of rules.
 * Stored at config/howtogo/<world>/place_details.json, per world since the dimension is part of the
 * key; debounced while typing and written atomically.
 * orphans() lists records no present place accounts for. Absence proves deletion only for sources
 * read whole (Xaero, RoadWeaver) or unavailable; MTR and Create read windows, so their records are
 * reported only when the source itself is gone. Player places are decided by the node id.
 */
public final class PlaceDetailStore {

    /** How long the store must be untouched before it is written, matching the road network's. */
    private static final long AUTOSAVE_DELAY_MS = 3000L;

    /** The name of the file in the world's data directory; see WorldFiles#worldFile. */
    private static final String FILE_NAME = "place_details";

    /** The records of the bound world, in file order. Replaced whole on a bind or a reload. */
    private static Map<PlaceKey, PlaceDetails> records = new LinkedHashMap<>();

    private static Object boundLevel;
    private static Path boundPath;
    private static boolean dirty;
    private static long lastMutationMillis;

    private PlaceDetailStore() {
    }

    /**
     * One stored record that no current place accounts for.
     * @param key     which place it was about
     * @param details what the player wrote, for a maintenance screen
     */
    public record Orphan(PlaceKey key, PlaceDetails details) {
    }


    /**
     * The key of a destination in the player's current dimension, or null when it is not a place. Null
     * for a map pin: it stands for a clicked spot, not for stored data.
     */
    public static PlaceKey keyOf(Destination place) {
        if (place == null || place.source() == null || place.source().isBlank()) {
            return null;
        }
        return PlaceKeys.of(currentDimension(), place);
    }

    /** What the player wrote about a place, or PlaceDetails.EMPTY. */
    public static PlaceDetails detailsOf(Destination place) {
        PlaceKey key = keyOf(place);
        if (key == null) {
            return PlaceDetails.EMPTY;
        }
        ensureBound();
        PlaceDetails found = records.get(key);
        return found == null ? PlaceDetails.EMPTY : found;
    }

    public static boolean isFavorite(Destination place) {
        return detailsOf(place).favorite();
    }

    public static PlaceDetails detailsOf(PlaceKey key) {
        if (key == null) {
            return PlaceDetails.EMPTY;
        }
        ensureBound();
        PlaceDetails found = records.get(key);
        return found == null ? PlaceDetails.EMPTY : found;
    }

    public static String currentDimension() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? "unknown" : level.dimension().location().toString();
    }


    public static void put(Destination place, PlaceDetails details) {
        PlaceKey key = keyOf(place);
        if (key == null || details == null) {
            return;
        }
        ensureBound();
        PlaceDetails normalized = details.normalize().withUpdatedAt(System.currentTimeMillis());
        if (normalized.isEmpty()) {
            // A record that says nothing is the same as none; removing it makes clear undo the storage.
            if (records.remove(key) != null) {
                markDirty();
            }
            return;
        }
        if (!Objects.equals(records.put(key, normalized), normalized)) {
            markDirty();
        }
    }

    /** Stores a record under a key already resolved, for the maintenance screen. */
    public static void put(PlaceKey key, PlaceDetails details) {
        if (key == null || details == null) {
            return;
        }
        ensureBound();
        PlaceDetails normalized = details.normalize().withUpdatedAt(System.currentTimeMillis());
        if (normalized.isEmpty()) {
            remove(key);
            return;
        }
        if (!Objects.equals(records.put(key, normalized), normalized)) {
            markDirty();
        }
    }

    /** Deletes a place's record, if there is one. */
    public static void remove(Destination place) {
        PlaceKey key = keyOf(place);
        if (key != null) {
            remove(key);
        }
    }

    /** Deletes one record by key. */
    public static void remove(PlaceKey key) {
        if (key == null) {
            return;
        }
        ensureBound();
        if (records.remove(key) != null) {
            markDirty();
        }
    }

    public static void toggleFavorite(Destination place) {
        PlaceDetails current = detailsOf(place);
        put(place, current.withFavorite(!current.favorite()));
    }

    private static void markDirty() {
        dirty = true;
        lastMutationMillis = System.currentTimeMillis();
    }


    public static List<Orphan> orphans() {
        ensureBound();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || records.isEmpty()) {
            return List.of();
        }
        String dimension = currentDimension();

        Set<PlaceKey> present = new LinkedHashSet<>();
        for (Destination destination : Destinations.all()) {
            PlaceKey key = keyOf(destination);
            if (key != null) {
                present.add(key);
            }
        }

        List<Orphan> result = new ArrayList<>();
        for (Map.Entry<PlaceKey, PlaceDetails> entry : records.entrySet()) {
            PlaceKey key = entry.getKey();
            if (!dimension.equals(key.dimension())) {
                // Another dimension: its places are not loaded, so nothing can be said from here -- and it is not
                // offered for deletion.
                continue;
            }
            if (isUnlinked(key, present)) {
                result.add(new Orphan(key, entry.getValue()));
            }
        }
        result.sort(Comparator.comparing((Orphan orphan) -> orphan.key().source())
                .thenComparing(orphan -> orphan.key().id()));
        return result;
    }

    /** How many records orphans() would return. */
    public static int orphanCount() {
        return orphans().size();
    }

    /** Whether one stored record can be proven to have no place behind it any more. */
    private static boolean isUnlinked(PlaceKey key, Set<PlaceKey> present) {
        String source = key.source();
        if (PlaceSources.isManual(source)) {
            RoadNode node;
            try {
                node = RoadStore.get().node(Integer.parseInt(key.id().trim()));
            } catch (NumberFormatException notANode) {
                // A hand-placed place is keyed by a node id; an id that is not one came from an edited file.
                return true;
            }
            return node == null || node.type() != RoadNode.Type.POI;
        }
        if (PlaceSources.XAERO.equals(source) || PlaceSources.ROADWEAVER.equals(source)) {
            // Both lists are complete for this dimension, so absence is proof; an unavailable source produced
            // nothing, which is also proof.
            return !present.contains(key);
        }
        // MTR, Create and addons read windows, or the source may be gone; only an unavailable source is
        // evidence. See the class comment.
        return !sourceAvailable(source);
    }

    /** Whether a destination source is registered and currently has anything to offer. */
    private static boolean sourceAvailable(String sourceId) {
        DestinationSource source = DestinationSources.byId(sourceId);
        return source != null && source.isAvailable();
    }


    /** Called every client tick: follows level changes and runs the debounced autosave. */
    public static void tick() {
        ensureBound();
        if (dirty && System.currentTimeMillis() - lastMutationMillis >= AUTOSAVE_DELAY_MS) {
            saveNow();
        }
    }

    /** Writes the store out immediately if there are unsaved changes. */
    public static void saveNow() {
        if (!dirty || boundPath == null) {
            return;
        }
        if (PlaceStorage.save(boundPath, records)) {
            dirty = false;
            HowToGo.LOGGER.info("[HowToGo] saved {} place detail record(s) to {}",
                    records.size(), boundPath.getFileName());
        }
    }

    public static void reload() {
        ensureBound();
        if (boundPath == null) {
            return;
        }
        records = PlaceStorage.load(boundPath);
        dirty = false;
        HowToGo.LOGGER.info("[HowToGo] reloaded {} place detail record(s) from {}",
                records.size(), boundPath.getFileName());
    }

    /** Rebinds to the current level's world directory, flushing and reloading when it changes. */
    private static void ensureBound() {
        Minecraft minecraft = Minecraft.getInstance();
        Object level = minecraft.level;

        if (level == null) {
            if (boundLevel != null) {
                saveNow();
                boundLevel = null;
                boundPath = null;
                records = new LinkedHashMap<>();
                dirty = false;
            }
            return;
        }
        if (level == boundLevel && boundPath != null) {
            return;
        }
        if (boundLevel != null) {
            saveNow();
        }
        boundLevel = level;
        boundPath = WorldFiles.worldFile(FILE_NAME);
        records = PlaceStorage.load(boundPath);
        dirty = false;
        HowToGo.LOGGER.info("[HowToGo] place details: {} record(s) from {}",
                records.size(), boundPath.getFileName());
    }
}
