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
 * The entry point that runs on a server, which exists for the MTR sync.
 *
 * <h2>Why a client-side mod has one</h2>
 * Nothing this mod draws, plans or measures happens on a server: the roads are the client's own data,
 * the routing runs on the client, and every pixel is drawn by Xaero there. What a server <em>can</em>
 * do is hold MTR's whole railway in memory, and a client that is neither hosting the world nor running
 * MTR Map Overlay cannot see it. This class is where that becomes a feature: on a server with both MTR
 * and this mod, the railway is read here and sent to the clients that ask for it -- see
 * {@code MtrServerSync}. On the client side the same reading is preferred over the overlay's snapshot,
 * and the overlay is fallen back to only when no server of ours answered.
 *
 * <p>What is registered here is exactly that: the network channel and the server tick that checks MTR.
 * Nothing else runs on a server, and with MTR absent nothing happens at all.
 *
 * <h2>How the two loaders express "exactly one of these runs"</h2>
 * NeoForge gives the author two {@code @Mod} classes, one {@code dist = CLIENT} and one
 * {@code dist = DEDICATED_SERVER}, and constructs exactly one of them. Fabric's equivalent is the
 * entrypoint list in {@code fabric.mod.json}: this class is the {@code main} entrypoint, which runs on
 * a dedicated server <em>and</em> on a physical client, and {@link HowToGo} is the {@code client}
 * entrypoint, which Fabric skips on a dedicated server. Registering a payload type twice would be an
 * error, so on Fabric each entrypoint registers only its own half -- see {@code MtrSync}.
 */
// The class is written twice rather than conditioned inside, because the two loaders disagree about
// what an entry point *is*: NeoForge constructs a class and hands it the mod bus, Fabric calls a
// no-argument method on a class it instantiates itself. The javadoc above stays outside the condition:
// a `/** ... */` inside the `/* ... */` a condition is written with would close that comment early.
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
        // The payload types and the server tick are attached to the buses by annotation in MtrNet,
        // which FML scans for on its own; nothing has to be subscribed from here. Constructing this
        // class is what makes a dedicated server load the mod at all -- and what makes the log say so
        // rather than leaving an installed server looking like it did nothing.
        MtrSync.onServerInit();
        HowToGo.LOGGER.info("[HowToGo] constructed (dedicated server: MTR sync only)");
    }
}
//?}