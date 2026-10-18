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
 * The way in.
 *
 * <h2>What this is for</h2>
 * Another mod has four things it may want from this one: to contribute a destination source, to ask
 * what this mod knows about the world, to store a little data of its own beside the player's roads, or
 * to state a layer of points and lines of its own. Each of those exists somewhere in this mod already,
 * but behind classes that know which Minecraft and which loader they are on and that are free to be
 * reorganised between versions. This is the surface that is not: it says what an addon may depend on,
 * and everything it says is intended to keep working.
 *
 * <h2>Nothing to declare, nothing to check in</h2>
 * There is no registration ceremony and no object to obtain: the methods are static and callable as
 * soon as the addon's own code runs, and the registries behind them are safe to write to from any
 * thread. An addon may register in its own constructor, or wait for
 * {@link HowToGoRegistrationEvent}, which is the mod's way of saying "everything is loaded, now is a
 * good moment" -- see that class for the difference.
 *
 * <h2>Client side only</h2>
 * This mod is a client mod: every method here is meaningful only where the game runs, and calling it
 * from a dedicated server would fail at class loading rather than answer anything. Addons should
 * declare the dependency with {@code side = "CLIENT"} and subscribe on {@code Dist.CLIENT}.
 */
public final class HowToGoApi {

    /**
     * The version of this API, not of the mod.
     *
     * <p>One number, raised when something here changes in a way an addon could notice: a method
     * removed, a method's meaning changed, a default that used to answer one way answering another.
     * New methods do not raise it -- an addon compiled against an older API keeps working against a
     * newer mod, and that is the compatibility this number exists to make checkable rather than
     * assumed.
     */
    public static final int API_VERSION = 1;

    private HowToGoApi() {
    }

    /**
     * Whether this mod is present and this API is callable.
     *
     * <p>Always true, and deliberately a method rather than nothing at all: an addon that wants to
     * work with and without this mod has to ask something, and asking this is cheaper and more honest
     * than asking the mod list for the mod's id -- which the addon may have got wrong, and which
     * reports "absent" for a mod that loaded fine when the id was mistyped.
     */
    public static boolean isPresent() {
        return true;
    }

    /**
     * This mod's own version, as its mod container reports it.
     *
     * <p>Read from the container rather than written down here, so the number an addon shows in its
     * own diagnostics and the number in the jar cannot drift apart. Never throws: an addon calling
     * this from the wrong moment gets an empty string rather than an exception.
     */
    public static String version() {
        try {
            // The two loaders answer "what version of this mod is loaded" from different places and
            // in different shapes: NeoForge hands back a ModInfo whose version is a MavenVersion,
            // Fabric a ModMetadata whose version is a Version. Both are asked for a friendly string,
            // which is the same thing the player sees in the mod list.
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
     * Contributes a destination source to the picker, the maps and the destination list.
     *
     * <p>Equivalent to {@link DestinationSources#register(DestinationSource)}; here as well so that a
     * caller reading this class finds everything it can do in one place.
     */
    public static void registerDestinationSource(DestinationSource source) {
        DestinationSources.register(source);
    }

    /**
     * Contributes a layer of points and lines, drawn with the player's roads.
     *
     * <p>Equivalent to {@link RoadDataSources#register(String, RoadDataSource)}. The layer is the
     * addon's own: this mod keeps a copy of what the source states, draws it on the world map, and
     * never writes it into the player's road file, never offers it to the editor and never plans a
     * route through it.
     *
     * <p>The source is read whenever its {@link RoadDataSource#version()} differs from the number read
     * beside the layer being drawn, which is the whole of the change protocol: bump that number when
     * the data changes and this mod re-reads on its next tick; leave it alone and nothing happens.
     *
     * @param id     names the layer in the log, and is refused if already in use
     * @param source what states the layer; {@code version()} is asked every client tick
     */
    public static void registerRoadDataSource(String id, RoadDataSource source) {
        RoadDataSources.register(id, source);
    }

    /**
     * Contributes a check to {@code /howtogo selftest}.
     *
     * <p>Equivalent to {@link SelfChecks#register(SelfCheck)}.
     */
    public static void registerSelfCheck(SelfCheck check) {
        SelfChecks.register(check);
    }

    /**
     * Queues work for the next client tick.
     *
     * <p>Equivalent to {@link ClientScheduler#nextTick(Runnable)}. This is the answer to "my command
     * opened a screen and the screen went away": a command runs inside the chat screen's handling of
     * the line that was typed, and the work has to leave that handling before touching the game screen.
     */
    public static void runNextClientTick(Runnable action) {
        ClientScheduler.nextTick(action);
    }

    /**
     * A file of the addon's own, beside the world's road data.
     *
     * <p>{@code config/howtogo/<world>/<dimension><suffix>.json}, which is where this mod keeps
     * everything that is per world and per dimension, and for the same reason: on a multiplayer server
     * the client cannot read the save folder at all, and coordinates only mean anything within one
     * dimension. A suffix of {@code "-mydata"} gives one file per world and dimension; the suffix is
     * sanitised, so nothing an addon passes can escape the world's directory.
     *
     * <p>Useful only once a level is loaded: with no level there is no world to name, and the path
     * points at a directory for "unknown". The addon owns what it writes there -- this mod never opens
     * a file it did not name itself.
     *
     * @throws NullPointerException if the suffix is null
     */
    public static Path dataFile(String suffix) {
        return WorldFiles.of(Objects.requireNonNull(suffix, "suffix"));
    }
}
