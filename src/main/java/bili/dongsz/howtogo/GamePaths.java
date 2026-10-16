package bili.dongsz.howtogo;

import java.nio.file.Path;
//? if fabric {
/*import net.fabricmc.loader.api.FabricLoader;
*///?} else {
import net.neoforged.fml.loading.FMLPaths;
//?}

/**
 * The handful of loader-provided directories this mod writes into.
 *
 * <h2>Why this is its own class</h2>
 * Four classes ask for the config directory -- {@code WorldFiles} and {@code RoutePreferenceStore}
 * for this mod's own files, {@code MapFilter} and {@code MtrMarks} for theirs -- and the answer comes
 * from a different place on each loader: NeoForge exposes {@code FMLPaths.CONFIGDIR}, Fabric an
 * accessor on the loader.
 *
 * <p>Asking here rather than at each call site is not only about not repeating the condition. A
 * {@code //?} marker is a line comment, so a condition written mid-expression comments out the rest
 * of the line and leaves the statement unterminated -- which is what happened when this was first
 * tried inline. A method body has room for the marker on a line of its own.
 */
public final class GamePaths {

    private GamePaths() {
    }

    /** The game's config directory: {@code .minecraft/config} on a normal install. */
    public static Path configDir() {
        //? if fabric {
        /*return FabricLoader.getInstance().getConfigDir();
        *///?} else {
        return FMLPaths.CONFIGDIR.get();
        //?}
    }

    /** This mod's own directory inside it, where the per-save data files live. */
    public static Path modConfigDir() {
        return configDir().resolve(HowToGo.MODID);
    }
}
