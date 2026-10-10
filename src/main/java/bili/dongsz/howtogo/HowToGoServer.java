package bili.dongsz.howtogo;

import bili.dongsz.howtogo.item.ModItems;
import net.fabricmc.api.ModInitializer;

/**
 * The entry point that runs on both sides, and it exists for one reason: the item.
 *
 * <h2>Why a client-side mod needs one</h2>
 * Nothing this mod does for a player happens on a server: roads are the client's own data, the routing
 * runs on the client, every pixel is drawn by Xaero on the client. Installing it on a server is
 * therefore pointless -- and the mod says so in its README.
 *
 * <p>What it is <em>not</em> is free to be absent, and that is what this class is about. A recipe is a
 * data pack file, and a dedicated server reads the same data packs a client does: without an item
 * registered on that side, {@code data/howtogo/recipes/navigator.json} fails to parse and the server
 * logs an error about an unknown registry key every time it starts. Worse for the player, the server
 * owns the inventory in multiplayer, so an item only the client knows about cannot be crafted, held or
 * dropped -- the navigator would be a single-player-only item.
 *
 * <p>So the item is registered here, and nothing else is: no config (the client's config is a client
 * config), no event listeners, no rendering, no data. What the item <em>does</em> is still the client's
 * business -- see {@code NavigatorClient} and {@code NavigatorItem}, where every client-only line sits
 * behind a side check.
 *
 * <h2>How the split is expressed on Fabric</h2>
 * NeoForge gave the author two {@code @Mod} classes, one {@code dist = CLIENT} and one
 * {@code dist = DEDICATED_SERVER}, and constructed exactly one of them. Fabric's equivalent is the
 * entrypoint list in {@code fabric.mod.json}: this class is the {@code main} entrypoint, which runs on
 * a dedicated server <em>and</em> on a physical client, and {@link HowToGo} is the {@code client}
 * entrypoint, which is skipped on a dedicated server. Item registration therefore happens exactly once
 * on each side -- the same guarantee the two annotated classes gave, expressed in the loader's own
 * vocabulary rather than in an annotation.
 *
 * <p>One consequence worth stating plainly, because it is a property of this design rather than of the
 * port: because the mod is declared for both environments, a server that has it installed will have the
 * item, and a server that does not have it will not -- exactly as on NeoForge.
 */
public final class HowToGoServer implements ModInitializer {

    @Override
    public void onInitialize() {
        ModItems.register();
        HowToGo.LOGGER.info("[HowToGo] constructed (common entry point: item registration only)");
    }
}
