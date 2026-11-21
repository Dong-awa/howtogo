package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.minecraft.client.Minecraft;

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
 * The overlay's buttons only set() its BooleanValues at runtime and never write the TOML, so every
 * session starts with both layers drawn again. This class reads the overlay's values reflectively
 * (optional, not a dependency), saves the pair when the player changes them, and restores it once a
 * world is open next session. The pair lives in this mod's own file; the overlay's values are reached
 * only through its own getter and setter, so a build whose setter persists also writes its own file.
 * No preference of this mod's: the values saved are the ones the player clicked; the file exists
 * only so a click survives the game closing.
 *
 * <p>The overlay renamed its own classes in 1.5.2 ({@code com.lx862.mtrmap} became
 * {@code io.github.teamcreating.mtrmap}) and kept the field names, so both are tried, newest first.
 * 1.6.0 also writes the pair back to its own config itself, which makes this memory a bridge rather
 * than a necessity from that release on: it still restores what was saved before, and observing the
 * two values costs two reads a tick.
 */
public final class MtrOverlayToggles {

    private static final String MOD_ID = "mtrmap";

    /**
     * Its config holder: a singleton with one BooleanValue field per map layer, under each package the
     * overlay has given its classes.
     */
    private static final String[] CONFIG_NAMES = {
            "io.github.teamcreating.mtrmap.config.MTRMapConfig",
            "com.lx862.mtrmap.config.MTRMapConfig",
    };

    private static final String FILE_NAME = "mtr_overlay_toggles.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();


    private static boolean resolved;
    private static boolean available;
    private static boolean warned;

    private static Object instance;
    private static Field routeField;
    private static Field trackField;
    private static Method getMethod;
    private static Method setMethod;


    private static boolean loaded;
    private static boolean restored;
    private static boolean failed;

    private static Boolean lastRoute;
    private static Boolean lastTrack;

    private static Boolean savedRoute;
    private static Boolean savedTrack;

    private MtrOverlayToggles() {
    }

    /**
     * One client tick: restores the pair on the first tick with a world, then watches for changes.
     * The overlay's config is only fully loaded by then, and the two reads per tick cost less than the
     * world check.
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
                HowToGo.logInfo("[HowToGo] restored MTR Map Overlay's map toggles "
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

    static boolean available() {
        return resolve();
    }

    static boolean classesPresent() {
        for (String name : CONFIG_NAMES) {
            try {
                Class.forName(name, false, MtrOverlayToggles.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException | LinkageError absent) {
                // The older name, if this build predates the move.
            }
        }
        return false;
    }

    /**
     * Whether the shapes this reads are the overlay's, checked without a game or its config.
     * For the regression harness: the class is loaded but not initialised, so only names are looked at,
     * and a renamed field reports itself here. False also when the overlay is absent.
     */
    static boolean shapeReadable() {
        for (String name : CONFIG_NAMES) {
            try {
                Class<?> config = Class.forName(name, false, MtrOverlayToggles.class.getClassLoader());
                config.getField("INSTANCE");
                Field route = config.getField("routeLinesEnabled");
                config.getField("trackLinesEnabled");
                Class<?> value = route.getType();
                value.getMethod("get");
                value.getMethod("set", Object.class);
                return true;
            } catch (ClassNotFoundException | NoSuchFieldException absent) {
                // Not this name; try the next one, and report false when none has the shapes.
            } catch (ReflectiveOperationException | LinkageError | RuntimeException absent) {
                return false;
            }
        }
        return false;
    }

    /**
     * Looks up the overlay's config singleton and its two values once.
     * Loading without initialising is what lets the harness check the names against a real overlay jar.
     */
    private static boolean resolve() {
        if (resolved) {
            return available;
        }
        resolved = true;
        if (!modLoaded()) {
            return false;
        }
        for (String name : CONFIG_NAMES) {
            try {
                Class<?> config = Class.forName(name, true, MtrOverlayToggles.class.getClassLoader());
                Field singleton = config.getField("INSTANCE");
                Object value = singleton.get(null);
                if (value == null) {
                    throw new IllegalStateException("MTRMapConfig.INSTANCE is null");
                }
                instance = value;
                routeField = config.getField("routeLinesEnabled");
                trackField = config.getField("trackLinesEnabled");
                getMethod = routeField.getType().getMethod("get");
                // ConfigValue.set(T) erases to set(Object), the same method on every release of the config library.
                setMethod = routeField.getType().getMethod("set", Object.class);
                available = true;
                return true;
            } catch (ClassNotFoundException absent) {
                // The older name, if this build predates the move.
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failed) {
                available = false;
                warnUnavailable(failed);
                return false;
            }
        }
        available = false;
        warnUnavailable(new ClassNotFoundException(String.join(" or ", CONFIG_NAMES)));
        return false;
    }


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

    private static Path file() {
        Path config = bili.dongsz.howtogo.GamePaths.configDir();
        return config == null ? null : config.resolve(HowToGo.MODID).resolve(FILE_NAME);
    }

    private static final class ToggleDto {
        int version;
        boolean routeLines;
        boolean trackLines;
    }


    private static boolean modLoaded() {
        try {
            //? if fabric {
            /*return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(MOD_ID);
            *///?} else {
            return net.neoforged.fml.ModList.get() != null
                    && net.neoforged.fml.ModList.get().isLoaded(MOD_ID);
            //?}
        } catch (RuntimeException | LinkageError notBootstrapped) {
            return false;
        }
    }

    private static void warnUnavailable(Throwable cause) {
        if (warned) {
            return;
        }
        warned = true;
        HowToGo.logInfo("[HowToGo] MTR Map Overlay's map toggles are not readable under this "
                + "version ({}); they are left exactly as it sets them", cause.toString());
    }
}
