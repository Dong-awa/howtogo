package bili.dongsz.howtogo.compat.mcphone;

import bili.dongsz.howtogo.HowToGo;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.ui.IPhonePage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.util.List;

/**
 * HowToGo's app inside MCphone.
 * MCphone discovers addon apps with java.util.ServiceLoader: it reads META-INF/services/com.november.mcphone.api.client.app.IPhoneApp from every jar and instantiates each name. This jar contributes one such file, which is the whole registration.
 * Because that file is only read by MCphone's lookup, this class is never loaded when MCphone is absent and no com.november.mcphone reference is resolved -- there is no linkage error. MCphone is compileOnly for the same reason.
 * Invariant: nothing outside this package may reference anything in it, or a shared class would fail with NoClassDefFoundError on a client without MCphone.
 */
public final class HowToGoPhoneApp implements IPhoneApp {

    /** One app, one id: MCphone keys the icon, page and install record on it. */
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("howtogo", "navigator");

    /**
     * The icon: the mod's own navigator texture, shared with the pack icon in fabric.mod.json. It stays under textures/item/ because the texture outlived the item.
     */
    private static final ResourceLocation ICON =
            ResourceLocation.fromNamespaceAndPath("howtogo", "textures/item/navigator.png");

    /**
     * Public and implicit on purpose: ServiceLoader needs a no-argument constructor, and making it private would silently stop the app from existing.
     */
    public HowToGoPhoneApp() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("app.howtogo.navigator");
    }

    @Override
    public ResourceLocation getIconTexture() {
        return ICON;
    }

    /** Never called: opensInsidePhone() stays true, so MCphone uses openPage(). */
    @Override
    public void onPress() {
    }

    @Override
    public IPhonePage openPage() {
        return new DestinationPickerPage();
    }

    /** Listed without going through the store; there is nothing to buy or install. */
    @Override
    public boolean isPreinstalled() {
        return true;
    }

    /** Always available: this class cannot be loaded unless the mod is present. */
    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getVersion() {
        // Read from the mod container, so the number the phone shows and the jar's cannot drift apart.
        return ModList.get().getModContainerById(HowToGo.MODID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("");
    }

    @Override
    public String getAuthor() {
        return "DongSZ";
    }

    @Override
    public String getDescription() {
        return Component.translatable("app.howtogo.navigator.desc").getString();
    }

    /**
     * Nothing to declare: the app is discovered through HowToGo's own jar, so requiring it would be redundant.
     */
    @Override
    public List<com.november.mcphone.api.client.app.RequiredMod> requiredMods() {
        return List.of();
    }
}
