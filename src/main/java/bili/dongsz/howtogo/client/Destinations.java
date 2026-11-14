package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.api.DestinationSources;
import bili.dongsz.howtogo.place.PlaceSources;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.DestinationSource;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates every registered DestinationSource.
 * A view over DestinationSources, so every caller sees the same sources in the same order.
 */
public final class Destinations {

    private Destinations() {
    }

    /** Every source, built-in and registered, in picker order. */
    public static List<DestinationSource> sources() {
        return DestinationSources.all();
    }

    /** Every destination from every available source, in source order. */
    public static List<Destination> all() {
        List<Destination> result = new ArrayList<>();
        for (DestinationSource source : DestinationSources.all()) {
            if (source.isAvailable()) {
                result.addAll(source.destinations());
            }
        }
        return result;
    }

    /**
     * The destinations that are places, in source order.
     * All three map views must mark the same set (DestinationSource#marksPlaces); Xaero waypoints are destinations but not places.
     */
    public static List<Destination> places() {
        List<Destination> result = new ArrayList<>();
        for (DestinationSource source : DestinationSources.all()) {
            if (source.marksPlaces() && source.isAvailable()) {
                result.addAll(source.destinations());
            }
        }
        return result;
    }

    /** Whether a source's entries are places, for callers that only have a source id. */
    public static boolean isPlaceSource(String sourceId) {
        return DestinationSources.marksPlaces(sourceId);
    }

    /**
     * What to call a nameless station: where it is.
     * The fallback is a whole translated sentence shared by both station sources, so a Create and an
     * MTR station are not listed two different ways.
     */
    public static String stationName(String name, int x, int z) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return Component.translatable("hud.howtogo.station.name",
                String.valueOf(x), String.valueOf(z)).getString();
    }

    /**
     * Whether a destination is a place this mod can hold details about.
     * A map-picked point is a destination but comes from no source and has no record, so it is not a
     * place. Asked before the details panel opens.
     */
    public static boolean isPlace(Destination destination) {
        if (destination == null || destination.source() == null
                || destination.source().isBlank()) {
            return false;
        }
        return PlaceSources.isManual(destination.source())
                || DestinationSources.byId(destination.source()) != null;
    }

    /**
     * What the reader is told a place's source is.
     * Derived, not stored: the source is where the place came from and a stored copy would go stale.
     * Built-in ids get keys; an addon is asked its display name; an unknown id shows as itself.
     */
    public static String sourceLabel(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return "";
        }
        String key = PlaceSources.labelKey(sourceId);
        if (!key.isEmpty()) {
            return translated(key, sourceId);
        }
        DestinationSource source = DestinationSources.byId(sourceId);
        if (source != null) {
            String display = source.displayName();
            if (display != null && !display.isBlank()) {
                return translated(display, sourceId);
            }
        }
        return sourceId;
    }

    /** The short form of sourceLabel, for a badge on a row or marker. */
    public static String sourceShortLabel(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return "";
        }
        String key = PlaceSources.shortLabelKey(sourceId);
        if (!key.isEmpty()) {
            return translated(key, sourceId);
        }
        return sourceLabel(sourceId);
    }

    /** A translation, or the fallback when the key has nothing behind it. */
    private static String translated(String key, String fallback) {
        String text = Component.translatable(key).getString();
        // Component.translatable falls back to the key, and a dotted key is worse than the honest id.
        return text.equals(key) ? fallback : text;
    }
}

