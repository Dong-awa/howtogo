package bili.dongsz.howtogo;

import bili.dongsz.howtogo.api.ApiBootstrap;
import bili.dongsz.howtogo.api.ClientScheduler;
import bili.dongsz.howtogo.client.Narration;
import bili.dongsz.howtogo.client.NavHudRenderer;
import bili.dongsz.howtogo.client.Navigation;
import bili.dongsz.howtogo.client.AutoSelfTest;
import bili.dongsz.howtogo.client.MtrClientData;
import bili.dongsz.howtogo.client.RailTrackStore;
import bili.dongsz.howtogo.client.MapFilterOverlay;
import bili.dongsz.howtogo.client.RoadEditHandler;
import bili.dongsz.howtogo.client.RoadEditSession;
import bili.dongsz.howtogo.client.RoadLayer;
import bili.dongsz.howtogo.client.RoadStore;
import bili.dongsz.howtogo.client.TransitLineStore;
import bili.dongsz.howtogo.item.ModItems;
import bili.dongsz.howtogo.store.RoutePreferenceStore;
import bili.dongsz.howtogo.webmap.WebMapService;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Entry point -- the mod's only one.
 * This mod is deliberately client-only: roads are stored on the client and every feature
 * (rendering, editing, routing) is a pure client concern for now.
 *
 * <h2>The three loader-level differences from the NeoForge original</h2>
 * <ol>
 *   <li><b>Forge requires a no-argument constructor.</b> NeoForge injects the mod event bus and the
 *       {@code ModContainer} into whatever constructor shape a {@code @Mod} class declares; Forge
 *       1.20.1 reflects on the class looking for {@code <init>()} and fails the whole mod with
 *       {@code NoSuchMethodException} if it is not there. The bus is therefore fetched from
 *       {@link FMLJavaModLoadingContext}. (Measured, not assumed: the first dedicated-server run of
 *       this port died on exactly that exception. The Fabric branch of this same port needed the
 *       no-argument form too, for its own loader's reasons.)</li>
 *   <li><b>Forge's {@code @Mod} has no {@code dist} parameter.</b> The original was annotated
 *       {@code @Mod(value = ..., dist = Dist.CLIENT)} and relied on the loader never constructing it
 *       on a dedicated server, with a second class {@code HowToGoServer} taking that side. Forge
 *       1.20.1 exposes only {@code value()}, so there is nowhere to put that decision in an annotation
 *       -- and a second {@code @Mod} class carrying the same modid is not a substitute, because Forge
 *       constructs every such class on every side, which would produce two containers for one mod and
 *       a duplicate item registration. The side is therefore asked directly, once, at the top of
 *       {@link #initialise}, and everything client-only sits behind that answer.</li>
 *   <li><b>Forge fires {@code TickEvent.ClientTickEvent} once per phase</b> (START and END), where
 *       NeoForge's {@code ClientTickEvent.Post} was a separate event for the tail of the tick. The
 *       per-tick listener therefore checks {@code phase == Phase.END}, which is exactly the behaviour
 *       the original's {@code .Post} listeners had.</li>
 * </ol>
 *
 * <h2>What happened to the original's two entry points</h2>
 * The author split this into a client class and a dedicated-server class ({@code HowToGoServer}) so
 * that a server would register the navigator item and nothing else. That split is preserved here in
 * substance -- {@link #initialise} registers the item and then returns before naming a single
 * client-only class unless {@code FMLEnvironment.dist == Dist.CLIENT} -- but it is one class rather
 * than two, because the annotation no longer carries the dist and Forge would construct both. The
 * reasoning the author recorded for needing the item on a server is unchanged and still applies: a
 * recipe is a data pack file, a dedicated server reads the same data packs a client does, and without
 * an item registered on that side {@code data/howtogo/recipes/navigator.json} fails to parse -- and in
 * multiplayer the server owns the inventory, so an item only the client knew about could not be
 * crafted, held or dropped at all.
 */
@Mod(value = HowToGo.MODID)
public final class HowToGo {

    public static final String MODID = "howtogo";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * One of this mod's own diagnostics, written only when the player has asked for them.
     *
     * <h2>What counts as a diagnostic</h2>
     * A line about how the mod is <em>working</em> rather than about what happened to the player: the
     * rail layer's one line a second, the cost of a map frame, what an MTR reading turned into, the
     * arithmetic of a planned route, the geometry of a line drawn as a straight step. All of it is
     * worth having while something is being investigated and none of it is worth writing once a second
     * for a whole session, so it goes through here and the switch is {@link RoadConfig#debugLog()}.
     *
     * <p>Everything that is not a diagnostic stays on the logger directly: warnings and errors, which
     * are always reported because a problem a player cannot see is a problem nobody can fix, and the
     * handful of one-off lines that say the mod loaded, saved or listened -- those are the record of a
     * session rather than measurements of it.
     */
    public static void diagnostic(String format, Object... args) {
        if (RoadConfig.debugLog()) {
            LOGGER.info(format, args);
        }
    }

    /**
     * The constructor Forge calls. It must have exactly this signature -- see the class comment.
     */
    public HowToGo() {
        this(FMLJavaModLoadingContext.get().getModEventBus(), null);
    }

    /**
     * The constructor NeoForge would have called, kept because it is what a reader coming from the
     * original expects to find and because it costs nothing. Both forms funnel into
     * {@link #initialise}, so there is exactly one place where listeners are registered and no way for
     * the two to drift apart.
     */
    public HowToGo(IEventBus modEventBus, ModContainer modContainer) {
        initialise(modEventBus);
    }

    /**
     * Everything the mod does at construction time, on whichever side it is being constructed.
     */
    private void initialise(IEventBus modEventBus) {
        // The item exists on both sides, for the recipe reason the class comment records. This is the
        // first statement so that a dedicated server -- which returns below -- still gets it.
        ModItems.register(modEventBus);

        // The side is asked before a single client-only listener is named. FMLEnvironment.dist is a
        // plain enum comparison that touches nothing client-only, so this is safe to evaluate on a
        // server -- and unlike the annotation the original used, it is a guarantee rather than a
        // promise about what the loader does with a class it is given no dist for.
        if (FMLEnvironment.dist != Dist.CLIENT) {
            LOGGER.info("[HowToGo] constructed (dedicated server: item registration only)");
            return;
        }

        // Registration into Xaero's render pipeline has to wait until Xaero has built its
        // handler, so we poll from the client tick instead of doing it here.
        MinecraftForge.EVENT_BUS.addListener(HowToGo::onClientTickEnd);
        // Client commands: opening the terminal, ending the trip and switching travel mode are all
        // client state, so they run here with no server involved and no permission needed.
        MinecraftForge.EVENT_BUS.addListener(HowToGoCommand::onRegisterClientCommands);

        // Editing keys arrive as screen events and editing clicks as low-level input events, and the two
        // paths are not interchangeable in this build: the keyboard handler offers a key to the open
        // screen first and returns before posting InputEvent.Key if the screen took it, while the mouse
        // handler posts its event before any screen sees the click. RoadEditHandler carries the detail.
        //
        // NeoForge's InputEvent.MouseButton.Pre/Post exist on Forge 1.20.1 under the same names, which
        // is why the Forge branch needs no Mixin where the Fabric branch needed one to see a raw click.
        MinecraftForge.EVENT_BUS.addListener(RoadEditHandler::onMouseButton);
        MinecraftForge.EVENT_BUS.addListener(RoadEditHandler::onMouseButtonReleased);
        MinecraftForge.EVENT_BUS.addListener(RoadEditHandler::onScreenKeyPressed);
        MinecraftForge.EVENT_BUS.addListener(RoadEditHandler::onScreenKeyReleased);
        // The map's switches: a screen overlay rather than part of the map, so that nothing the map
        // draws can end up over them. See MapFilterOverlay.
        MinecraftForge.EVENT_BUS.addListener(MapFilterOverlay::onScreenRender);
        MinecraftForge.EVENT_BUS.addListener(MapFilterOverlay::onMousePressed);
        MinecraftForge.EVENT_BUS.addListener(MapFilterOverlay::onMouseDragged);
        MinecraftForge.EVENT_BUS.addListener(MapFilterOverlay::onMouseReleased);
        // Answered per frame rather than per tick, so it belongs with the render listeners: the HUD's
        // own draw call. RenderGuiEvent.Post is the same event the original listened to.
        MinecraftForge.EVENT_BUS.addListener(NavHudRenderer::onRenderGui);

        modEventBus.addListener(RoadEditHandler::onRegisterKeyMappings);
        ModItems.registerCreativeTab(modEventBus);
        // A config screen's save has to reach the values this mod holds rather than reads where it
        // uses them. Almost every key is read at the point of use and needs nothing; the route
        // preferences and the default travel mode are seeded once, so saving the screen used to leave
        // them with no effect until the game was restarted -- while the keys beside them in the same
        // screen took effect at once, which is what made it read as a broken screen.
        //
        // ModConfigEvent is a mod-bus event on both loaders, so this stays on modEventBus exactly as
        // it was; only the class moved.
        modEventBus.addListener((ModConfigEvent.Reloading event) -> {
            Navigation.reloadConfiguredMode();
            RoutePreferenceStore.load();
        });
        // Client setup runs after the config files have been read, so this is the first point at
        // which the configured travel mode is genuinely available -- and the only point at which
        // the saved route preferences sit in front of config values that are known to be loaded.
        modEventBus.addListener((FMLClientSetupEvent event) -> {
            Navigation.loadConfiguredMode();
            RoutePreferenceStore.load();
        });
        // Config registration moved off ModContainer: on Forge 1.20.1 the container exposes only
        // addConfig(ModConfig), and the (Type, IConfigSpec) entry point the original used lives on
        // ModLoadingContext instead. Same effect, and still declared CLIENT so the file stays
        // config/howtogo-client.toml.
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, RoadConfig.SPEC);

        LOGGER.info("[HowToGo] constructed");
    }

    /**
     * The one per-tick listener, and the reason the tick body is a method rather than fifteen lambdas.
     *
     * <h2>Why one listener and not fifteen</h2>
     * Forge's {@code ClientTickEvent} is fired twice a tick, so each listener must filter on the phase;
     * fifteen lambdas each carrying the same three-line guard is fifteen places for the guard to be
     * forgotten. One listener with one guard, calling the jobs in the order the original registered them
     * on {@code NeoForge.EVENT_BUS}, keeps that order readable in a single place:
     * <ol>
     *   <li>{@code RoadLayer} -- Xaero's render pipeline has to be joined before anything draws;</li>
     *   <li>the addon API's two jobs, deferred queue first, then the registration event -- so work
     *       queued by a registration handler waits for the next tick, which is the promise
     *       {@code ClientScheduler} and {@code ApiBootstrap} document;</li>
     *   <li>the four stores that read their own files;</li>
     *   <li>the browser map's server, when the config asks it to start itself;</li>
     *   <li>navigation, which is what the stores are for;</li>
     *   <li>the commands' deferred screen work -- a screen cannot be opened from inside the command
     *       that asked for it, so it runs on the tick after;</li>
     *   <li>narration, which is fed rather than drawn;</li>
     *   <li>the in-game self-test, silent unless a launcher asked for one.</li>
     * </ol>
     *
     * <p>The order matters and is the original's: {@code RoadLayer} first, the API jobs before the
     * stores, and navigation after the stores that feed it.
     */
    private static void onClientTickEnd(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        RoadLayer.onClientTick();
        ClientScheduler.tick();
        ApiBootstrap.fireOnce();
        RoadStore.tick();
        TransitLineStore.tick();
        RailTrackStore.tick();
        MtrClientData.tick();
        RoadEditSession.tick();
        WebMapService.tick();
        Navigation.tick();
        HowToGoCommand.tick();
        Narration.tick();
        AutoSelfTest.tick();
    }

    /**
     * Which side this process is, for the classes that have to ask.
     *
     * <p>Public because {@code AutoSelfTest} needs the same answer: a self-test that cannot say whether
     * it ran on a client or a server is not evidence of anything.
     */
    public static boolean isClient() {
        return FMLEnvironment.dist == Dist.CLIENT;
    }
}
