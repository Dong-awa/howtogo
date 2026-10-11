package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.route.DestinationSource;
import net.neoforged.bus.api.Event;

/**
 * "This mod is loaded and its API is ready" -- the moment an addon may register.
 *
 * <h2>When it is posted</h2>
 * Once, on the first client tick, on the client thread, after every mod has been constructed and
 * after client config has been read. Posted on the game event bus ({@code NeoForge.EVENT_BUS}), so an
 * addon reaches it the ordinary way:
 *
 * <pre>{@code
 * @EventBusSubscriber(modid = "myaddon", value = Dist.CLIENT)
 * public final class MyAddon {
 *     @SubscribeEvent
 *     public static void onRegister(HowToGoRegistrationEvent event) {
 *         event.registerDestinationSource(new MySource());
 *     }
 * }
 * }</pre>
 *
 * <h2>Why not "as soon as I construct"</h2>
 * Because mod construction order is not a contract. An addon that posts into this mod's registries
 * from its own constructor works only when it happens to be constructed second, and fails silently
 * -- an empty list, not an error -- when it is constructed first. Waiting for a moment this mod
 * announces removes the ordering question entirely; the first client tick is a moment every mod is
 * already loaded by.
 *
 * <h2>Why the client thread</h2>
 * So a handler may look at the game if it needs to: read a config, inspect a loaded world, build a
 * source whose availability depends on both. The earlier, loader-side moments an addon might use for
 * this are not guaranteed to run on the client thread.
 *
 * <h2>Not the only way in</h2>
 * The registries in {@link HowToGoApi} are open and thread-safe from the start: an addon whose
 * registration must happen earlier -- before a resource pack, say -- may call them directly and skip
 * this event. Both routes end in the same list, and an id may only be taken once, so registering twice
 * is refused rather than doubled.
 */
public final class HowToGoRegistrationEvent extends Event {

    /**
     * Constructed by this mod when it posts the event; addons only ever receive one.
     */
    public HowToGoRegistrationEvent() {
    }

    /** Contributes a destination source; see {@link HowToGoApi#registerDestinationSource}. */
    public void registerDestinationSource(DestinationSource source) {
        DestinationSources.register(source);
    }

    /** Contributes a self-check; see {@link HowToGoApi#registerSelfCheck}. */
    public void registerSelfCheck(SelfCheck check) {
        SelfChecks.register(check);
    }
}
