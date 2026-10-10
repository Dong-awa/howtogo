package bili.dongsz.howtogo.item;

import bili.dongsz.howtogo.HowToGo;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

/**
 * Item registration.
 *
 * <h2>What the Fabric port had to change</h2>
 * On NeoForge this was a {@code DeferredRegister.Items} handed to the mod event bus, and the same bus
 * carried the creative-tab listener that put the navigator in a tab. Fabric has no deferred register on
 * a mod container and no tab event object: registration is a direct
 * {@link Registry#register(net.minecraft.core.Registry, ResourceLocation, Object)} call, and a tab is
 * extended through {@code ItemGroupEvents.modifyEntriesEvent}, which is registered once and fires when
 * the game builds that tab.
 *
 * <p>{@code NAVIGATOR} consequently changed type from {@code DeferredItem<Item>} to {@link Item}: there
 * is no holder to defer through. Nothing outside this class read it, so no call site changed.
 */
public final class ModItems {

    public static final Item NAVIGATOR = new NavigatorItem(new Item.Properties().stacksTo(1));

    private ModItems() {
    }

    /** Registers the item and puts it in the creative tab. Called from the common entry point. */
    public static void register() {
        Registry.register(BuiltInRegistries.ITEM,
                new ResourceLocation(HowToGo.MODID, "navigator"), NAVIGATOR);
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
                .register(entries -> entries.accept(NAVIGATOR));
    }
}
