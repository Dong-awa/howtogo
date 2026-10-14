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

    // registerItem, not register. The two look interchangeable and are not: since 1.21.2 an
    // Item.Properties has to carry the id it will be registered under, and register(name, supplier)
    // passes the supplier's own Properties through untouched. The item then registers fine and
    // throws the first time anything asks it for its id -- which is during RegisterEvent, so the
    // mod does not load at all:
    //
    //     HowToGo (howtogo) encountered an error while dispatching the
    //     net.neoforged.neoforge.registries.RegisterEvent event
    //     java.lang.NullPointerException: Item id not set
    //
    // registerItem builds the Properties with the name already in it, which is the whole reason the
    // overload exists. The same trap bit the per-version branches this tree replaced; it is not
    // specific to any one node, and this file is shared by all eight.
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
