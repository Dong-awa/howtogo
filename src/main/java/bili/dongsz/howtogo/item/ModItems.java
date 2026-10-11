package bili.dongsz.howtogo.item;

import bili.dongsz.howtogo.HowToGo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Item registration.
 *
 * <h2>Why registering twice is harmless here</h2>
 * The author's original had two {@code @Mod} classes -- a client one and a dedicated-server one that
 * registered the item and nothing else -- because NeoForge's {@code @Mod} takes a {@code dist} and so
 * the loader itself could choose which class was constructed where. Forge 1.20.1 has no such parameter
 * and constructs every {@code @Mod} class on every side, so two classes sharing a modid would give two
 * containers for one mod and a duplicate item registration; this port therefore has a single entry
 * point, and the "item only, on a server" half of the split lives in {@code HowToGo.initialise}
 * returning early instead.
 *
 * <p>{@link #register} is still written to be idempotent rather than relying on being called exactly
 * once. It is cheap, it removes an ordering assumption that a future refactor could quietly break, and
 * <b>{@code DeferredRegister.register} genuinely does throw on a second call</b> -- so the guard is
 * load-bearing, not decorative.
 *
 * <p>The creative-tab listener is added once, by {@link #registerCreativeTab}, which
 * {@code HowToGo} calls on the client only: adding it twice would put two copies of the navigator in
 * the creative tab.
 */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, HowToGo.MODID);

    public static final RegistryObject<Item> NAVIGATOR = ITEMS.register("navigator",
            () -> new NavigatorItem(new Item.Properties().stacksTo(1)));

    /** Guards the {@code DeferredRegister} being attached to the mod event bus more than once. */
    private static boolean attached;

    private ModItems() {
    }

    /**
     * Attaches the registry to the mod event bus. Safe to call from both entry points on both sides.
     *
     * <p>NeoForge's {@code DeferredRegister.Items} convenience class has no Forge counterpart, so this
     * is an ordinary {@code DeferredRegister<Item>} over {@code Registries.ITEM}; the registration and
     * the {@code RegistryObject} it yields are the same shape either way.
     */
    public static void register(IEventBus modEventBus) {
        if (attached) {
            return;
        }
        attached = true;
        ITEMS.register(modEventBus);
    }

    /**
     * Makes the navigator appear in the creative tab it belongs in.
     *
     * <p>Client only, and called only from the client entry point: {@code BuildCreativeModeTabContentsEvent}
     * is a mod-bus event that Forge fires on the client for the creative screen, and a dedicated server
     * has no creative screen to fill.
     */
    public static void registerCreativeTab(IEventBus modEventBus) {
        modEventBus.addListener(ModItems::onBuildCreativeTab);
    }

    private static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        ResourceKey<?> tab = event.getTabKey();
        if (CreativeModeTabs.TOOLS_AND_UTILITIES.equals(tab)) {
            event.accept(NAVIGATOR);
        }
    }
}
