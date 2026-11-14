package bili.dongsz.howtogo;

import bili.dongsz.howtogo.mtr.MtrSync;
//? if fabric {
/*import net.fabricmc.api.ModInitializer;
*///?} else {
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
//?}

/**
 * Server entry point; exists only for MTR sync.
 * Nothing is drawn or planned server-side. A server that has MTR can read the whole railway
 * and send it to asking clients (see MtrServerSync), and the client prefers that over MTR
 * Map Overlay's snapshot. Registers only the network channel and the MTR-check tick.
 * NeoForge instantiates one @Mod class per dist; Fabric's main entrypoint runs on both sides
 * while HowToGo is the client one, so each registers only its own half -- see MtrSync.
 */
// The class is written twice instead of conditioned inside: NeoForge constructs a class with
// the mod bus, Fabric calls a no-arg method; this javadoc stays outside the /* */ condition.
//? if fabric {
/*public final class HowToGoServer implements ModInitializer {

    @Override
    public void onInitialize() {
        // The main entrypoint runs on a dedicated server and on a client that hosts a world, so this
        // is where the server half of the sync comes up on Fabric -- and where an integrated server
        // gets it too, which is what makes a LAN world sync without anybody installing anything extra.
        MtrSync.onServerInit();
        HowToGo.LOGGER.info("[HowToGo] constructed (common entry point: MTR sync only)");
    }
}
*///?} else {
@Mod(value = HowToGo.MODID, dist = Dist.DEDICATED_SERVER)
public final class HowToGoServer {

    public HowToGoServer(IEventBus modEventBus, ModContainer modContainer) {
        // Payload types and the server tick are attached by annotation in MtrNet; constructing this
        // class is what makes a dedicated server load the mod at all.
        MtrSync.onServerInit();
        HowToGo.LOGGER.info("[HowToGo] constructed (dedicated server: MTR sync only)");
    }
}
//?}