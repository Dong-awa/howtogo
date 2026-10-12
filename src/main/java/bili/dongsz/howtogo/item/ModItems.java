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

    // registerItem (not register) is required from 1.21.4 on: NeoForge fills the item id into
    // Item.Properties before calling the factory, and the Item constructor asserts that id is
    // present. The plain supplier overload leaves it null -> NullPointerException("Item id not
    // set") inside the RegisterEvent, which aborts mod loading.
    public static final DeferredItem<Item> NAVIGATOR = ITEMS.registerItem("navigator",
            properties -> new NavigatorItem(properties.stacksTo(1)));

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
