package bili.dongsz.howtogo.item;

import bili.dongsz.howtogo.HowToGo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
//? if fabric {
/*import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
*///?} else {
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
//?}

/**
 * Item registration.
 *
 * <h2>Why the two loaders are written out separately here</h2>
 * NeoForge registers through a {@code DeferredRegister.Items} handed to the mod event bus, and the
 * same bus carries the creative-tab listener. Fabric has neither: registration is a direct
 * {@code Registry.register} call, and a tab is extended through {@code ItemGroupEvents}, which is
 * registered once and fires when the game builds that tab. There is no expression that covers both,
 * so the halves are written out.
 *
 * <p>{@code NAVIGATOR}'s type differs with them -- {@code DeferredItem<Item>} on NeoForge, plain
 * {@link Item} on Fabric, because Fabric has no holder to defer through. Nothing outside this class
 * reads it, so no call site has to care.
 */
public final class ModItems {

    //? if fabric {
    /*public static final Item NAVIGATOR = new NavigatorItem(new Item.Properties().stacksTo(1));
    *///?} else {
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
    // specific to any one node, and this file is shared by every one of them.
    public static final DeferredItem<Item> NAVIGATOR = ITEMS.registerItem("navigator",
            properties -> new NavigatorItem(properties.stacksTo(1)));
    //?}

    private ModItems() {
    }

    // Registers the item and puts it in the creative tab.
    //
    // Takes no bus: Fabric's registration is immediate rather than deferred, and the tab listener
    // registers itself against the event the game fires when it builds that tab.
    //
    // The comment is `//` lines rather than javadoc on purpose. A `/** ... */` inside the `/* ... */`
    // a loader condition is written with closes that comment early, and what follows becomes live
    // code on every node -- the same trap MIGRATION.md records for whole-file conditions.
    //? if fabric {
    /*public static void register() {
        Registry.register(BuiltInRegistries.ITEM,
                new ResourceLocation(HowToGo.MODID, "navigator"), NAVIGATOR);
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
                .register(entries -> entries.accept(NAVIGATOR));
    }
    *///?} else {
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
    //?}
}
