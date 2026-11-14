package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.client.WorldFiles;
import bili.dongsz.howtogo.route.DestinationSource;
//? if fabric {
/*import net.fabricmc.loader.api.FabricLoader;
*///?} else {
import net.neoforged.fml.ModList;
//?}

import java.nio.file.Path;
import java.util.Objects;

/**
 * The way in: the surface an addon may depend on.
 *
 * Four things an addon may want from this mod: contribute a destination source, ask what it
 * knows about the world, store data beside the player's roads, or state a layer of points and
 * lines. Each already exists behind classes that may be reorganised between versions; these
 * static methods are the part intended to keep working.
 *
 * No registration ceremony: registries are safe to write from any thread, and
 * HowToGoRegistrationEvent says when everything is loaded.
 *
 * Client side only: on a dedicated server this fails at class loading. Declare the dependency
 * with side = "CLIENT" and subscribe on Dist.CLIENT.
 */
public final class HowToGoApi {

    /** The version of this API, not of the mod; raised only when an addon could notice a change. */
    public static final int API_VERSION = 1;

    private HowToGoApi() {
    }

    /**
     * Whether this mod is present and this API is callable; always true, and cheaper than a mod-id lookup.
     */
    public static boolean isPresent() {
        return true;
    }

    /**
     * This mod's own version from its mod container; an empty string rather than an exception on a bad call.
     */
    public static String version() {
        try {
            //? if fabric {
            /*return FabricLoader.getInstance().getModContainer(HowToGo.MODID)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("");
            *///?} else {
            return ModList.get().getModContainerById(HowToGo.MODID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("");
            //?}
        } catch (Throwable notLoadedYet) {
            return "";
        }
    }

    /**
     * Contributes a destination source; same as DestinationSources#register, here so callers find everything in one place.
     */
    public static void registerDestinationSource(DestinationSource source) {
        DestinationSources.register(source);
    }

    /**
     * Contributes a layer of points and lines, drawn with the player's roads.
     * The layer is the addon's own: this mod draws it but never writes it into the player's road
     * file, offers it to the editor or routes through it. The source is re-read when its version()
     * differs from the number stored beside the layer, so bump that number to refresh it.
     * @param id names the layer in the log, refused if already in use
     * @param source what states the layer; version() is asked every client tick
     */
    public static void registerRoadDataSource(String id, RoadDataSource source) {
        RoadDataSources.register(id, source);
    }

    public static void registerSelfCheck(SelfCheck check) {
        SelfChecks.register(check);
    }

    /**
     * Queues work for the next client tick; the answer to "my command opened a screen and it
     * went away", because a command runs inside the chat screen's handling.
     * Same as ClientScheduler#nextTick.
     */
    public static void runNextClientTick(Runnable action) {
        ClientScheduler.nextTick(action);
    }

    /**
     * A file of the addon's own: config/howtogo/<world>/<dimension><suffix>.json, per world and
     * dimension because a multiplayer client cannot read the save folder and coordinates only
     * mean something within one dimension. The suffix is sanitised, and the addon owns the file.
     * Useful only once a level is loaded; with none the path names "unknown".
     * @throws NullPointerException if the suffix is null
     */
    public static Path dataFile(String suffix) {
        return WorldFiles.of(Objects.requireNonNull(suffix, "suffix"));
    }
}
