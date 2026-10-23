//? if neoforge && >=26.1 {
/*package bili.dongsz.howtogo;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// Registers the client config, in whichever shape the NeoForge version offers.
//
// From 1.20.5 the container does it: registerConfig(Type, IConfigSpec) and the container builds the
// ModConfig itself. Before that the container only accepts a finished one, addConfig(ModConfig), so
// the ModConfig has to be constructed at the call site.
//
// The config screen is registered here only where it exists. Three arms, not two, because the two
// pieces of it arrived at different versions:
//
//   1.20.4   neither IConfigScreenFactory nor ConfigurationScreen
//   1.20.6   IConfigScreenFactory only -- the interface landed a release before the screen
//   1.21.1+  both, which is the shape used here
//
// The 1.20.6 arm therefore registers the config and no screen: there is nothing to pass as the
// factory. This is measured, not assumed -- the classes were checked in each node's own NeoForge jar.
//
// The reason this is a helper rather than a condition at the call is the same as HowToGoTicks: the
// call is inside HowToGo's neoforge arm, which is a whole-class fence, so a version condition there
// would nest. Every arm here is a top-level condition on both axes, so on fabric the file compiles
// to nothing.
final class HowToGoConfig {

    private HowToGoConfig() {
    }

    static void registerClient(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, RoadConfig.SPEC);
        // The config screen: the button beside this mod in the mods list. NeoForge's own editor
        // reads the very SPEC the file is written from -- the same sections, defaults, comments and
        // ranges -- so nothing here is a second copy of the settings that could drift from the file,
        // and a value saved in the screen is a value the next reload of the file sees. Without this
        // no screen is offered at all, which is why the two colour sections would otherwise only be
        // editable by hand in the TOML.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
*///?}
//? if neoforge && >=1.21.1 && <26.1 {
package bili.dongsz.howtogo;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// 1.21.1 through 1.21.11. See the first arm of this file for why this is a helper and why the
// config screen is only registered where both halves of it exist.
final class HowToGoConfig {

    private HowToGoConfig() {
    }

    static void registerClient(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, RoadConfig.SPEC);
        // The config screen: the button beside this mod in the mods list. NeoForge's own editor
        // reads the very SPEC the file is written from -- the same sections, defaults, comments and
        // ranges -- so nothing here is a second copy of the settings that could drift from the file.
        // Without this no screen is offered at all, which is why the colour sections would otherwise
        // only be editable by hand in the TOML. This arm covered the whole 1.20.5..26.1 range until
        // the screen registration was split out; leaving it out is what made the Config button
        // vanish on the 1.21.x nodes.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
//?}
//? if neoforge && >=1.20.5 && <1.21.1 {
/*package bili.dongsz.howtogo;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;

// 1.20.6 only: IConfigScreenFactory exists but ConfigurationScreen does not, so the config is
// registered and no screen is offered -- there is nothing to pass as the factory.
final class HowToGoConfig {

    private HowToGoConfig() {
    }

    static void registerClient(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, RoadConfig.SPEC);
    }
}
*///?}
//? if neoforge && <1.20.5 {
/*package bili.dongsz.howtogo;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;

// The pre-1.20.5 shape. See the other arm of this file for why it is a helper.
final class HowToGoConfig {

    private HowToGoConfig() {
    }

    static void registerClient(ModContainer container) {
        // The container only takes a finished ModConfig here, and the three-argument constructor is
        // the one that leaves the file name to be derived from the mod id.
        container.addConfig(new ModConfig(ModConfig.Type.CLIENT, RoadConfig.SPEC, container));
    }
}
*///?}
