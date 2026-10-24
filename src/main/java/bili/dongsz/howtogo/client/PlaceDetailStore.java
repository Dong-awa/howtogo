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
 * Client-side holder for the place-detail records of the world currently loaded.
 *
 * <h2>Why a store of its own, beside {@link RoadStore}</h2>
 * Three of the five place sources are not in the player's road network at all: an MTR station, an
 * Xaero waypoint and a RoadWeaver structure are read out of another mod and never written down by
 * this one. Their notes therefore cannot live on a {@code RoadNode}, and putting the player's own
 * notes in a second place -- a field on the node -- would have meant two storage paths with two sets
 * of rules. One record type keyed by {@link PlaceKey} covers every source the same way, and the fact
 * that the player's own places also happen to be nodes stays an implementation detail of the deletion
 * rule, not of the storage.
 *
 * <h2>Where it lives</h2>
 * {@code config/howtogo/<world>/place_details.json}: per world rather than per dimension, because the
 * dimension is part of the record's key. It is written the same way the roads are -- debounced while
 * the player types, atomically when it is written at all -- so the file is never half a file.
 *
 * <h2>What "unlinked" means, and what it deliberately does not</h2>
 * An automatic source can stop producing a place: a station is dismantled, a waypoint deleted, the
 * mod removed. The detail record stays -- losing the player's notes because another mod changed would
 * be data loss -- and turns up in {@link #orphans()}, which the map-data screen offers to clean up.
 *
 * <p>Membership in that list is decided by what can be <em>proven</em>. Xaero's waypoints and
 * RoadWeaver's structures are read whole, so a record whose id is not in their current list is
 * certainly gone. MTR's and Create's readings are windowed to what is near the player: a station
 * outside that window is not in the list either, and calling it gone would offer to delete a note
 * about a station the player is simply standing away from. Those two, and an addon's source, are
 * therefore reported only when the source itself is unavailable -- the mod is gone or the integration
 * is switched off -- which is the case the list exists for. The player's own places are decided by
 * the node id: the network is the complete list.
 */
public final class PlaceDetailStore {

    /** How long the store must be untouched before it is written out, matching the road network's. */
    private static final long AUTOSAVE_DELAY_MS = 3000L;

    /** The name of the file in the world's data directory; see {@link WorldFiles#worldFile}. */
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
     *
     * @param key     which place it was about
     * @param details what the player wrote, so a maintenance screen can say what would be deleted
     */
    public record Orphan(PlaceKey key, PlaceDetails details) {
    }

    // ------------------------------------------------------------------ reading

    /**
     * The key of a destination in the dimension the player is in, or null when it is not a place.
     *
     * <p>Null for a point picked on the map: that destination stands for a spot the player clicked,
     * not for anything stored, so there is no record to find and nothing to write.
     */
    public static PlaceKey keyOf(Destination place) {
        if (place == null || place.source() == null || place.source().isBlank()) {
            return null;
        }
        return PlaceKeys.of(currentDimension(), place);
    }

    /** What the player wrote about a place, or {@link PlaceDetails#EMPTY} when nothing. */
    public static PlaceDetails detailsOf(Destination place) {
        PlaceKey key = keyOf(place);
        if (key == null) {
            return PlaceDetails.EMPTY;
        }
        ensureBound();
        PlaceDetails found = records.get(key);
        return found == null ? PlaceDetails.EMPTY : found;
    }

    /** Whether a place is a favourite. False for anything not stored, including a map pin. */
    public static boolean isFavorite(Destination place) {
        return detailsOf(place).favorite();
    }

    /** The detail record with this exact key, or {@link PlaceDetails#EMPTY}. */
    public static PlaceDetails detailsOf(PlaceKey key) {
        if (key == null) {
            return PlaceDetails.EMPTY;
        }
        ensureBound();
        PlaceDetails found = records.get(key);
        return found == null ? PlaceDetails.EMPTY : found;
    }

    /** The dimension id the store is keyed on now, or {@code "unknown"} before a world is loaded. */
    public static String currentDimension() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? "unknown" : level.dimension().location().toString();
    }

    // ------------------------------------------------------------------ writing

    /**
     * Stores what the player wrote about a place, stamping it with the current time.
     *
     * <p>Normalised here rather than trusted from the caller: this is the one gate every edit path
     * goes through -- the dialog, the favourite toggle, a future command -- so a limit that is wrong
     * in one screen cannot let a 4000-character description reach the file.
     */
    public static void put(Destination place, PlaceDetails details) {
        PlaceKey key = keyOf(place);
        if (key == null || details == null) {
            return;
        }
        ensureBound();
        PlaceDetails normalized = details.normalize().withUpdatedAt(System.currentTimeMillis());
        if (normalized.isEmpty()) {
            // A record that says nothing is the same as no record; deleting it is what makes
            // "clear the description" undo the storage rather than leave a row of blanks behind.
            if (records.remove(key) != null) {
                markDirty();
            }
            return;
        }
        if (!Objects.equals(records.put(key, normalized), normalized)) {
            markDirty();
        }
    }

    /** Stores a record under a key already resolved, for the maintenance screen's own records. */
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

    /** Flips a place's favourite flag, writing the detail immediately. */
    public static void toggleFavorite(Destination place) {
        PlaceDetails current = detailsOf(place);
        put(place, current.withFavorite(!current.favorite()));
    }

    /** Flags the store as changed so it will be written out shortly. */
    private static void markDirty() {
        dirty = true;
        lastMutationMillis = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ unlinked

    /**
     * The records of this dimension that no current place accounts for, sorted for a list.
     *
     * <p>See the class Javadoc for which sources this can prove an absence for. The list is built on
     * demand rather than kept: it is read when the maintenance screen opens, not while a map draws,
     * and holding it would mean keeping it in step with every store in the mod.
     */
    public static List<Orphan> orphans() {
        ensureBound();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || records.isEmpty()) {
            return List.of();
        }
        String dimension = currentDimension();

        // The destinations the game can see now, keyed the same way the store is. A source that is
        // unavailable contributes nothing, which is why its records fall through to the rules below.
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
                // A record about another dimension: the places there are not loaded, so nothing can
                // be said about it from here. It is deliberately not offered for deletion -- the
                // screen would otherwise list every note the player has ever written in the Nether
                // while they stand in the overworld.
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

    /** How many records {@link #orphans()} would return. */
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
                // A hand-placed place is keyed by a node id; an id that is not one is a record from a
                // file somebody edited, and it cannot name a node this session has.
                return true;
            }
            // Not a place any more -- deleted, or the id now names a road vertex -- is the same answer
            // as gone: the note describes a landmark that is no longer there.
            return node == null || node.type() != RoadNode.Type.POI;
        }
        if (PlaceSources.XAERO.equals(source) || PlaceSources.ROADWEAVER.equals(source)) {
            // Both sources' lists are complete for the dimension, so absence from one is proof. A
            // source that is unavailable produced no destinations at all, which is also proof.
            return !present.contains(key);
        }
        // MTR, Create and any addon: their lists are windows, or the source may be gone. Only an
        // unavailable source is evidence; see the class Javadoc.
        return !sourceAvailable(source);
    }

    /** Whether a destination source is registered and currently has anything to offer. */
    private static boolean sourceAvailable(String sourceId) {
        DestinationSource source = DestinationSources.byId(sourceId);
        return source != null && source.isAvailable();
    }

    // --------------------------------------------------------------- persistence

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

    /**
     * Re-reads the current world's store from disk, after something replaced the file.
     *
     * <p>Called when a map archive is imported over this world's data. The same reasoning as
     * {@link RoadStore#reload}: the in-memory records are the old file's, and the next autosave would
     * write them back over the imported ones.
     */
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
