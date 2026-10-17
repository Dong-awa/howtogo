package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Keeps MTR Map Overlay's two world-map toggle buttons where the player left them.
 *
 * <h2>The problem</h2>
 * The overlay draws its route lines and its track layer over Xaero's world map, and it puts two
 * toggle buttons in the map's corner to switch them off. Its two settings are
 * {@code ModConfigSpec.BooleanValue}s, and the buttons only {@code set()} them at runtime -- nothing
 * ever writes that back to the TOML -- so every session starts with both layers drawn again. A
 * player who turned them off to see this mod's markers has to do it again every time they launch
 * the game.
 *
 * <h2>The answer</h2>
 * This class remembers the two switches itself. It reads the overlay's values (reflectively: the
 * overlay is optional and not a dependency), writes the pair to its own file when the player changes
 * them, and puts the remembered pair back once a world is open in the next session. The overlay's
 * own config file is not touched, so uninstalling this mod leaves the overlay exactly as it was.
 *
 * <h2>What is remembered is not a preference of this mod's</h2>
 * No key is added to this mod's config and nothing is decided here about which layers are useful:
 * the values saved are the ones the player clicked, including "both on" if that is where they left
 * them. The file exists only so that a click survives the game closing.
 */
public final class MtrOverlayToggles {

    /** MTR Map Overlay's mod id. */
    private static final String MOD_ID = "mtrmap";

    /** Its config holder: a singleton with one {@code BooleanValue} field per map layer. */
    private static final String CONFIG = "com.lx862.mtrmap.config.MTRMapConfig";

    /** This class' own file, beside the map filter rather than in the overlay's config. */
    private static final String FILE_NAME = "mtr_overlay_toggles.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    // ---------------------------------------------------------------- reflection

    private static boolean resolved;
    private static boolean available;
    private static boolean warned;

    private static Object instance;
    private static Field routeField;
    private static Field trackField;
    private static Method getMethod;
    private static Method setMethod;

    // ------------------------------------------------------------------- state

    private static boolean loaded;
    private static boolean restored;
    private static boolean failed;

    /** The last values this class saw or wrote, so only a player's own change is saved. */
    private static Boolean lastRoute;
    private static Boolean lastTrack;

    /** What the file held when it was read, before it was applied. */
    private static Boolean savedRoute;
    private static Boolean savedTrack;

    private MtrOverlayToggles() {
    }

    /**
     * One client tick: restores the pair on the first tick with a world, then watches for changes.
     *
     * <p>Waits for a world because the overlay's config is only fully loaded by then, and because
     * the values cannot matter before there is a map to draw them on. The two reads per tick are
     * field reads on an object that is already in memory, so this is cheaper than the check for
     * whether a world is loaded.
     */
    public static void tick() {
        if (!resolve()) {
            return;
        }
        if (Minecraft.getInstance().level == null) {
            return;
        }
        Boolean route = read(routeField);
        Boolean track = read(trackField);
        if (route == null || track == null) {
            return;
        }
        if (!loaded) {
            loaded = true;
            load();
        }
        if (!restored) {
            restored = true;
            if (savedRoute != null && savedTrack != null) {
                apply(routeField, savedRoute);
                apply(trackField, savedTrack);
                route = savedRoute;
                track = savedTrack;
                HowToGo.LOGGER.info("[HowToGo] restored MTR Map Overlay's map toggles "
                        + "(routes {}, tracks {})", route ? "on" : "off", track ? "on" : "off");
            }
            lastRoute = route;
            lastTrack = track;
            return;
        }
        if (!route.equals(lastRoute) || !track.equals(lastTrack)) {
            save(route, track);
            lastRoute = route;
            lastTrack = track;
        }
    }

    /** Whether the overlay is installed and its two toggles could be bound. */
    static boolean available() {
        return resolve();
    }

    /** Whether the overlay's config class is on the classpath at all, asked without initialising it. */
    static boolean classesPresent() {
        try {
            Class.forName(CONFIG, false, MtrOverlayToggles.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /**
     * Whether the shapes this reads are the overlay's, checked without a game or its config.
     *
     * <p>For the regression harness: the class is loaded but not initialised, so the only thing
     * looked at is the field and method names, and a renamed field reports itself here rather than as
     * a quiet "nothing remembered". False also when the overlay is not on the classpath at all --
     * the caller asks {@link #classesPresent()} first if it needs to tell those apart.
     */
    static boolean shapeReadable() {
        try {
            Class<?> config = Class.forName(CONFIG, false, MtrOverlayToggles.class.getClassLoader());
            config.getField("INSTANCE");
            Field route = config.getField("routeLinesEnabled");
            config.getField("trackLinesEnabled");
            Class<?> value = route.getType();
            value.getMethod("get");
            value.getMethod("set", Object.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException absent) {
            return false;
        }
    }

    /**
     * Looks up the overlay's config singleton and its two values once.
     *
     * <p>Class loading without initialising first is what lets the harness check the names against a
     * real overlay jar: this needs the shapes, not the session's config.
     */
    private static boolean resolve() {
        if (resolved) {
            return available;
        }
        resolved = true;
        if (!modLoaded()) {
            return false;
        }
        try {
            Class<?> config = Class.forName(CONFIG, true, MtrOverlayToggles.class.getClassLoader());
            Field singleton = config.getField("INSTANCE");
            Object value = singleton.get(null);
            if (value == null) {
                throw new IllegalStateException("MTRMapConfig.INSTANCE is null");
            }
            instance = value;
            routeField = config.getField("routeLinesEnabled");
            trackField = config.getField("trackLinesEnabled");
            getMethod = routeField.getType().getMethod("get");
            // ConfigValue.set(T) erases to set(Object), which is the same method on every release of
            // the config library this mod was built against.
            setMethod = routeField.getType().getMethod("set", Object.class);
            available = true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failed) {
            available = false;
            warnUnavailable(failed);
        }
        return available;
    }

    // ------------------------------------------------------------------- values

    private static Boolean read(Field field) {
        if (field == null || instance == null || getMethod == null) {
            return null;
        }
        try {
            Object value = field.get(instance);
            Object read = value == null ? null : getMethod.invoke(value);
            return read instanceof Boolean flag ? flag : null;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            warnUnavailable(failed);
            return null;
        }
    }

    private static void apply(Field field, boolean value) {
        if (field == null || instance == null || setMethod == null) {
            return;
        }
        try {
            Object holder = field.get(instance);
            if (holder != null) {
                setMethod.invoke(holder, value);
            }
        } catch (ReflectiveOperationException | RuntimeException broke) {
            if (!failed) {
                failed = true;
                HowToGo.LOGGER.warn("[HowToGo] could not set MTR Map Overlay's map toggles: {}",
                        broke.toString());
            }
        }
    }

    // ------------------------------------------------------------------- storage

    /** Writes the pair, so the next session can put it back. */
    private static void save(boolean route, boolean track) {
        savedRoute = route;
        savedTrack = track;
        Path file = file();
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            ToggleDto dto = new ToggleDto();
            dto.version = 1;
            dto.routeLines = route;
            dto.trackLines = track;
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(dto, writer);
            }
        } catch (IOException failed) {
            HowToGo.LOGGER.warn("[HowToGo] could not write {}: {}", file, failed.toString());
        }
    }

    /** Reads the pair this class last saved, if there is one. */
    private static void load() {
        Path file = file();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ToggleDto dto = GSON.fromJson(reader, ToggleDto.class);
            if (dto != null && dto.version >= 1) {
                savedRoute = dto.routeLines;
                savedTrack = dto.trackLines;
            }
        } catch (IOException | JsonSyntaxException unreadable) {
            HowToGo.LOGGER.warn("[HowToGo] could not read {}; the overlay's own defaults are left "
                    + "alone", file, unreadable);
        }
    }

    /** Client-wide: the two buttons are about an interface, not about a world. */
    private static Path file() {
        Path config = FMLPaths.CONFIGDIR.get();
        return config == null ? null : config.resolve(HowToGo.MODID).resolve(FILE_NAME);
    }

    /** Field names are the on-disk contract, so they are deliberately terse and stable. */
    private static final class ToggleDto {
        int version;
        boolean routeLines;
        boolean trackLines;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean modLoaded() {
        try {
            return net.neoforged.fml.ModList.get() != null
                    && net.neoforged.fml.ModList.get().isLoaded(MOD_ID);
        } catch (RuntimeException | LinkageError notBootstrapped) {
            return false;
        }
    }

    /** Reports a failure once, then stays quiet. */
    private static void warnUnavailable(Throwable cause) {
        if (warned) {
            return;
        }
        warned = true;
        HowToGo.LOGGER.info("[HowToGo] MTR Map Overlay's map toggles are not readable under this "
                + "version ({}); they are left exactly as it sets them", cause.toString());
    }
}
