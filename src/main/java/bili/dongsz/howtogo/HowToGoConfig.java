//? if neoforge && >=1.20.5 {
package bili.dongsz.howtogo;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;

// Registers the client config, in whichever shape the NeoForge version offers.
//
// From 1.20.5 the container does it: registerConfig(Type, IConfigSpec) and the container builds the
// ModConfig itself. Before that the container only accepts a finished one, addConfig(ModConfig), so
// the ModConfig has to be constructed at the call site.
//
// The reason this is a helper rather than a condition at the call is the same as HowToGoTicks: the
// call is inside HowToGo's neoforge arm, which is a whole-class fence, so a version condition there
// would nest. Both arms here are top-level conditions on both axes, so on fabric the file compiles
// to nothing.
final class HowToGoConfig {

    private HowToGoConfig() {
    }

    static void registerClient(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, RoadConfig.SPEC);
    }
}
//?}
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
