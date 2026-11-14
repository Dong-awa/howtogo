package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.HowToGo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every addon road layer this process knows about, in registration order.
 * A registration is an id plus a RoadDataSource; the id names the layer in logs and refusal messages, and the source states when its version moves (read on the client tick).
 * Layers draw in registration order, under the player's own data. A duplicate id is refused rather than replaced, so log lines stay unambiguous.
 * Registration is safe from any thread (concurrent list, nothing to rebuild); reading is client-thread only.
 */
public final class RoadDataSources {

    /** The registered layers, in registration order. */
    private static final List<Entry> REGISTERED = new CopyOnWriteArrayList<>();

    private static final Set<String> IDS = ConcurrentHashMap.newKeySet();

    private RoadDataSources() {
    }

    /**
     * Adds a layer of points and lines contributed by another mod.
     * Registering an id twice does nothing and logs it: a registration is a caller in this process, not a resource with a lifetime, so there is nothing to replace or unregister.
     * @throws NullPointerException if the source is null (a caller bug)
     */
    public static void register(String id, RoadDataSource source) {
        if (source == null) {
            throw new NullPointerException("road data source");
        }
        if (id == null || id.isBlank()) {
            HowToGo.LOGGER.warn("[HowToGo] api | a road data source with no id was refused");
            return;
        }
        if (!IDS.add(id)) {
            HowToGo.LOGGER.warn("[HowToGo] api | road data source id '{}' is already in use; the later "
                    + "registration was refused", id);
            return;
        }
        REGISTERED.add(new Entry(id, source));
        HowToGo.LOGGER.info("[HowToGo] api | road data source registered: {}", id);
    }

    public static List<Entry> all() {
        return Collections.unmodifiableList(REGISTERED);
    }

    public static int registeredCount() {
        return REGISTERED.size();
    }

    public static List<String> ids() {
        List<String> ids = new ArrayList<>(REGISTERED.size());
        for (Entry entry : REGISTERED) {
            ids.add(entry.id());
        }
        return ids;
    }

    /**
     * One registration: the id the layer is named by, and the source that states it. A record so the two cannot drift apart.
     */
    public record Entry(String id, RoadDataSource source) {
    }
}
