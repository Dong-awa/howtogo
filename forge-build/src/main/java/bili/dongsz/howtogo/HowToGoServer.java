package bili.dongsz.howtogo;

import bili.dongsz.howtogo.item.ModItems;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * The dedicated-server entry point.
 *
 * <h2>Why a client-side mod needs one</h2>
 * Nothing this mod does for a player happens on a server: roads are the client's own data, the routing
 * runs on the client, every pixel is drawn by Xaero on the client. Installing it on a server is
 * therefore pointless -- and the mod says so in its README.
 *
 * <p>What it is <em>not</em> is free to be absent, and that is what this class is about. A recipe is a
 * data pack file, and a dedicated server reads the same data packs a client does: without an item
 * registered on that side, {@code data/howtogo/recipe/navigator.json} fails to parse and the server logs
 * an error about an unknown registry key every time it starts. Worse for the player, the server owns the
 * inventory in multiplayer, so an item only the client knows about cannot be crafted, held or dropped --
 * the navigator would be a single-player-only item.
 *
 * <p>So the item is registered here too, and nothing else is: no config (the client's config is a client
 * config), no event listeners, no rendering, no data. What the item <em>does</em> is still the client's
 * business -- see {@code NavigatorClient} and {@code NavigatorItem}, where every client-only line sits
 * behind a side check.
 *
 * <p>NeoForge constructs exactly one of the two entry points: this one on a dedicated server, and
 * {@link HowToGo} on a physical client (where the integrated server shares the client's registries). Two
 * classes rather than one guarded class, because a {@code dist}-annotated class is the one thing the
 * loader promises not to touch on the other side -- and a promise is better than a hand-written check.
 */
@Mod(value = HowToGo.MODID, dist = Dist.DEDICATED_SERVER)
public final class HowToGoServer {

    public HowToGoServer(IEventBus modEventBus, ModContainer modContainer) {
        ModItems.register(modEventBus);
        HowToGo.LOGGER.info("[HowToGo] constructed (dedicated server: item registration only)");
    }
}
