//? if fabric && >=1.21.2 {
/*package bili.dongsz.howtogo.item;

import bili.dongsz.howtogo.HowToGo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

// The properties an item this mod registers is built from, in the shape the version needs.
//
// Since 1.21.2 an Item.Properties has to carry the id it will be registered under. Nothing checks
// that at registration: an item built without one registers fine and then throws the first time
// anything asks it for its id -- and the first thing that asks is the Item constructor itself, so
// the mod does not load at all:
//
//     java.lang.NullPointerException: Item id not set
//         at net.minecraft.class_1792$class_1793.method_63689(class_1792.java:405)
//         at net.minecraft.class_1792.<init>(class_1792.java:144)
//         at bili.dongsz.howtogo.item.NavigatorItem.<init>(NavigatorItem.java:47)
//
// NeoForge avoids this with DeferredRegister.Items.registerItem, which builds the Properties with
// the name already in it; see the comment on ModItems.NAVIGATOR. Fabric has no such overload -- the
// registry takes whatever Properties the supplier made -- so the id is set here instead.
//
// This is a whole file rather than a condition inside ModItems because ModItems is already fenced
// by a loader condition, and a version condition inside one would nest, which Stonecutter's scopes
// do not allow. Both arms here are top-level conditions on both axes, so the file compiles to one
// small class on the Fabric nodes that need it and to nothing on NeoForge, which does not.
//
// Every comment in this file is `//` on purpose. A javadoc block ends with the same two characters
// the commented-out arm is written with, so one inside the arm closes it early and what follows
// becomes live code on the node that is not using that arm -- the same trap ModItems warns about.
public final class ItemProperties {

    private ItemProperties() {
    }

    // The id an item is registered under.
    //
    // The same value has to be used for the registry key and for the Properties, or the item's own
    // id and the key it lives under disagree; both call sites go through here so there is one place
    // to change and no way for them to drift.
    public static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath(HowToGo.MODID, name);
    }

    // The registry key for that id.
    public static ResourceKey<Item> key(String name) {
        return ResourceKey.create(Registries.ITEM, id(name));
    }

    // Properties for an item registered under the given name, one per stack.
    public static Item.Properties properties(String name) {
        return new Item.Properties().stacksTo(1).setId(key(name));
    }
}
*///?}
//? if fabric && <1.21.2 {
/*package bili.dongsz.howtogo.item;

import bili.dongsz.howtogo.HowToGo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

// The pre-1.21.2 shape. See the other arm of this file for why it is a file at all.
//
// Before 1.21.2 an Item.Properties carries no id: the registry key is the only place the name lives,
// so building the properties is all this has to do. The id accessor stays because the registration
// call site wants it either way.
public final class ItemProperties {

    private ItemProperties() {
    }

    // The id an item is registered under.
    //
    // The factory is a version question -- see HTG_RESOURCE_LOCATION in stonecutter.gradle.kts:
    // before 1.21 there is only the public constructor, from 1.21 only the static factory.
    public static ResourceLocation id(String name) {
        return HTG_RESOURCE_LOCATION(HowToGo.MODID, name);
    }

    // Properties for an item registered under the given name, one per stack.
    public static Item.Properties properties(String name) {
        return new Item.Properties().stacksTo(1);
    }
}
*///?}
