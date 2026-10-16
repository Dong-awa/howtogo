package bili.dongsz.howtogo;

import bili.dongsz.howtogo.item.ModItems;
//? if fabric {
/*import net.fabricmc.api.ModInitializer;
*///?} else {
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
//?}

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
 * <h2>How the two loaders express "exactly one of these runs"</h2>
 * NeoForge gives the author two {@code @Mod} classes, one {@code dist = CLIENT} and one
 * {@code dist = DEDICATED_SERVER}, and constructs exactly one of them -- and a {@code dist}-annotated
 * class is the one thing the loader promises not to touch on the other side, so a promise is doing the
 * work here rather than a hand-written check.
 *
 * <p>Fabric's equivalent is the entrypoint list in {@code fabric.mod.json}: this class is the
 * {@code main} entrypoint, which runs on a dedicated server <em>and</em> on a physical client, and
 * {@link HowToGo} is the {@code client} entrypoint, which Fabric skips on a dedicated server. Item
 * registration therefore happens exactly once on each side -- the same guarantee, expressed in the
 * loader's own vocabulary instead of in an annotation.
 *
 * <p>One consequence worth stating plainly, because it is a property of the design rather than of
 * either port: the mod is declared for both environments, so a server that has it installed will have
 * the item and a server that does not have it will not -- identically on both loaders.
 */
// The class is written twice rather than conditioned inside, because the two loaders disagree about
// what an entry point *is*: NeoForge constructs a class with a constructor it hands the mod bus to,
// Fabric calls a no-argument method on a class it instantiates itself. The body is two lines either
// way, so a shared shell with the differences inside would be more scaffolding than code.
//
// The javadoc above stays outside the condition: a `/** ... */` inside the `/* ... */` a condition is
// written with would close that comment early -- the trap MIGRATION.md records.
//? if fabric {
/*public final class HowToGoServer implements ModInitializer {

    @Override
    public void onInitialize() {
        ModItems.register();
        HowToGo.LOGGER.info("[HowToGo] constructed (common entry point: item registration only)");
    }
}
*///?} else {
@Mod(value = HowToGo.MODID, dist = Dist.DEDICATED_SERVER)
public final class HowToGoServer {

    public HowToGoServer(IEventBus modEventBus, ModContainer modContainer) {
        ModItems.register(modEventBus);
        HowToGo.LOGGER.info("[HowToGo] constructed (dedicated server: item registration only)");
    }
}
//?}
