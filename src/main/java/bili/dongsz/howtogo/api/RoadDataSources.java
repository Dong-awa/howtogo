package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.HowToGo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every addon road layer this process knows about, in registration order.
 *
 * <h2>What a registration is</h2>
 * An id and a {@link RoadDataSource}. The id names the layer in the log and in the refusal messages;
 * the source supplies the points and lines. The mod reads every registered source on the client tick
 * whenever the source says its version has moved -- see {@link RoadDataSource} for that contract.
 *
 * <h2>Order and duplicates</h2>
 * Layers are drawn in registration order, under everything the player's own data draws. An id already
 * in use is refused rather than replaced, in the same way and for the same reason as
 * {@link DestinationSources}: two layers answering to one id would make every log line about either of
 * them ambiguous.
 *
 * <h2>Threading</h2>
 * Registration is safe from any thread -- the list is concurrent and there is nothing to rebuild -- so
 * an addon may register from its own constructor, from {@link HowToGoRegistrationEvent}, or from a
 * config callback. Reading happens on the client thread only.
 */
public final class RoadDataSources {

    /** The registered layers, in registration order. */
    private static final List<Entry> REGISTERED = new CopyOnWriteArrayList<>();

    /** Every id in use, so a collision is refused rather than absorbed. */
    private static final Set<String> IDS = ConcurrentHashMap.newKeySet();

    private RoadDataSources() {
    }

    /**
     * Adds a layer of points and lines contributed by another mod.
     *
     * <p>Registering the same id twice changes nothing the second time and says so in the log: a
     * registration is a caller in this process, not a resource with a lifetime, so there is nothing to
     * replace and nothing to unregister.
     *
     * @throws NullPointerException if the source is null, which is a bug in the caller rather than a
     *                              condition to carry on from
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

    /** Every registered layer, in registration order. Never null. */
    public static List<Entry> all() {
        return REGISTERED;
    }

    /** How many layers have been registered. */
    public static int registeredCount() {
        return REGISTERED.size();
    }

    /** The ids of every registered layer, in registration order. */
    public static List<String> ids() {
        List<String> ids = new ArrayList<>(REGISTERED.size());
        for (Entry entry : REGISTERED) {
            ids.add(entry.id());
        }
        return ids;
    }

    /**
     * One registration: the id the layer is named by, and the source that states it.
     *
     * <p>A record rather than a map entry so that the two cannot drift apart, and so a caller that
     * walks {@link #all()} cannot accidentally reorder what it found.
     */
    public record Entry(String id, RoadDataSource source) {
    }
}
