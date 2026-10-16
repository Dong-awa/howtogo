package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.route.DestinationSource;
//? if fabric {
/*import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
*///?} else {
import net.neoforged.bus.api.Event;
//?}

/**
 * "This mod is loaded and its API is ready" -- the moment an addon may register.
 *
 * <h2>When it is posted</h2>
 * Once, on the first client tick, on the client thread, after every mod has been constructed and
 * after client config has been read.
 *
 * <h2>How an addon subscribes, per loader</h2>
 * On NeoForge this class extends {@code net.neoforged.bus.api.Event} and is posted on the game event
 * bus, so an addon reaches it the ordinary way:
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
 * Fabric's event API is built from {@code EventFactory} instead: there is no event base class and no
 * annotation to subscribe with, so the event publishes its own {@code EVENT} and an addon registers a
 * callback on it:
 *
 * <pre>{@code
 * public final class MyAddon implements ClientModInitializer {
 *     @Override
 *     public void onInitializeClient() {
 *         HowToGoRegistrationEvent.EVENT.register(event -> event.registerDestinationSource(new MySource()));
 *     }
 * }
 * }</pre>
 *
 * <p>On either loader an addon may subscribe from its own initialiser rather than from the event,
 * because the registries behind it are open from the start; the timing advice below is unchanged.
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
// The two halves differ by more than a base class: Fabric's event API has no common supertype, so the
// Fabric side has to declare the handler shape and the array-backed dispatch itself, where NeoForge
// gets both from extending Event. Written out in full rather than interleaved, for the same reason as
// the entry points -- the shared part would be the constructor and two delegating methods.
//
// Comments inside a condition are `//` lines, not javadoc: a `/** ... */` here would close the `/*`
// the condition is written with.
//? if fabric {
/*public final class HowToGoRegistrationEvent {

    // What an addon registers on EVENT; Fabric's substitute for @SubscribeEvent.
    @FunctionalInterface
    public interface Handler {
        void onRegistration(HowToGoRegistrationEvent event);
    }

    // The event itself. Handlers run in registration order; a throwing handler is the loader's problem.
    public static final Event<Handler> EVENT = EventFactory.createArrayBacked(Handler.class,
            handlers -> event -> {
                for (Handler handler : handlers) {
                    handler.onRegistration(event);
                }
            });

    // Constructed by this mod when it posts the event; addons only ever receive one.
    public HowToGoRegistrationEvent() {
    }

    public void registerDestinationSource(DestinationSource source) {
        DestinationSources.register(source);
    }

    public void registerSelfCheck(SelfCheck check) {
        SelfChecks.register(check);
    }
}
*///?} else {
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
//?}
