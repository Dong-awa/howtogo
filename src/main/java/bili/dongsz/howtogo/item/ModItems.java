package bili.dongsz.howtogo.item;

import bili.dongsz.howtogo.HowToGo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Item registration.
 */
public final class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(HowToGo.MODID);

    public static final DeferredItem<Item> NAVIGATOR = ITEMS.register("navigator",
            () -> new NavigatorItem(new Item.Properties().stacksTo(1)));

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(ModItems::onBuildCreativeTab);
    }

    private static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        ResourceKey<?> tab = event.getTabKey();
        if (CreativeModeTabs.TOOLS_AND_UTILITIES.equals(tab)) {
            event.accept(NAVIGATOR);
        }
    }
}
