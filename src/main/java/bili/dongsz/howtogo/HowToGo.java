package bili.dongsz.howtogo;

import bili.dongsz.howtogo.client.Narration;
import bili.dongsz.howtogo.client.NavHudRenderer;
import bili.dongsz.howtogo.client.Navigation;
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
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Entry point.
 * This mod is deliberately client-only: roads are stored on the client and every feature
 * (rendering, editing, routing) is a pure client concern for now.
 */
@Mod(value = HowToGo.MODID, dist = Dist.CLIENT)
public final class HowToGo {

    public static final String MODID = "howtogo";
    public static final Logger LOGGER = LogUtils.getLogger();

    public HowToGo(IEventBus modEventBus, ModContainer modContainer) {
        // Registration into Xaero's render pipeline has to wait until Xaero has built its
        // handler, so we poll from the client tick instead of doing it here.
        NeoForge.EVENT_BUS.addListener(RoadLayer::onClientTick);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> RoadStore.tick());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> TransitLineStore.tick());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> RailTrackStore.tick());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> MtrClientData.tick());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> RoadEditSession.tick());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> Navigation.tick());
        // Ticked rather than drawn: the readout is recomputed per frame, so deciding what to say
        // there would repeat the same instruction continuously, and the HUD does not run at all
        // while a screen is open. The speaking itself is on the narration thread; this only feeds it.
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> Narration.tick());
        NeoForge.EVENT_BUS.addListener(NavHudRenderer::onRenderGui);

        // Editing keys arrive as screen events and editing clicks as low-level input events, and the two
        // paths are not interchangeable in this build: the keyboard handler offers a key to the open
        // screen first and returns before posting InputEvent.Key if the screen took it, while the mouse
        // handler posts its event before any screen sees the click. RoadEditHandler carries the detail.
        NeoForge.EVENT_BUS.addListener(RoadEditHandler::onMouseButton);
        NeoForge.EVENT_BUS.addListener(RoadEditHandler::onMouseButtonReleased);
        NeoForge.EVENT_BUS.addListener(RoadEditHandler::onScreenKeyPressed);
        NeoForge.EVENT_BUS.addListener(RoadEditHandler::onScreenKeyReleased);
        // The map's switches: a screen overlay rather than part of the map, so that nothing the map
        // draws can end up over them. See MapFilterOverlay.
        NeoForge.EVENT_BUS.addListener(MapFilterOverlay::onScreenRender);
        NeoForge.EVENT_BUS.addListener(MapFilterOverlay::onMousePressed);
        NeoForge.EVENT_BUS.addListener(MapFilterOverlay::onMouseDragged);
        NeoForge.EVENT_BUS.addListener(MapFilterOverlay::onMouseReleased);

        modEventBus.addListener(RoadEditHandler::onRegisterKeyMappings);
        // Client setup runs after the config files have been read, so this is the first point at
        // which the configured travel mode is genuinely available -- and the only point at which
        // the saved route preferences sit in front of config values that are known to be loaded.
        modEventBus.addListener((FMLClientSetupEvent event) -> {
            Navigation.loadConfiguredMode();
            RoutePreferenceStore.load();
        });
        ModItems.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.CLIENT, RoadConfig.SPEC);

        LOGGER.info("[HowToGo] constructed");
    }
}
