package bili.dongsz.howtogo;

import java.nio.file.Path;
//? if fabric {
/*import net.fabricmc.loader.api.FabricLoader;
*///?} else {
import net.neoforged.fml.loading.FMLPaths;
//?}

/**
 * The loader-provided directories this mod writes into.
 * NeoForge exposes FMLPaths.CONFIGDIR and Fabric an accessor on the loader; a //? marker cannot sit
 * mid-expression, so the condition needs a method body.
 */
public final class GamePaths {

    private GamePaths() {
    }

    /** The game's config directory: .minecraft/config on a normal install. */
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
