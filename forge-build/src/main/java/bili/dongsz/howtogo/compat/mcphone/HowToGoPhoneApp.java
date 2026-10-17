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
 *
 * <h2>How this is found, and why that matters</h2>
 * MCphone discovers addon apps with {@link java.util.ServiceLoader}: it loads
 * {@code META-INF/services/com.november.mcphone.api.client.app.IPhoneApp} from every jar on the
 * classpath and instantiates each name in it. This jar contributes one such file naming this class,
 * which is the whole of the registration.
 *
 * <p>That mechanism is also what makes this an <b>adaptation and not a dependency</b>. The services
 * file is only ever read by MCphone's own lookup, so with MCphone absent this class is never loaded
 * and no reference to {@code com.november.mcphone} is ever resolved -- there is no linkage error to
 * avoid, because nothing asks. HowToGo installs and runs exactly as before on its own.
 *
 * <h2>The invariant that keeps that true</h2>
 * <b>Nothing outside this package may reference anything in it.</b> The moment some shared class
 * mentions {@code HowToGoPhoneApp}, loading that shared class would try to load this one, and with
 * MCphone absent that fails with {@code NoClassDefFoundError}. MCphone is a {@code compileOnly}
 * dependency for the same reason: compiled against, never bundled, never shipped.
 *
 * <p>MCphone 1.10.2 also requires the mod on the server as well as the client; HowToGo does not. The
 * split is documented in the README rather than worked around here.
 */
public final class HowToGoPhoneApp implements IPhoneApp {

    /** One app, one id: this is what MCphone keys the icon, the page and the install record on. */
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("howtogo", "navigator");

    /**
     * The icon, reusing the navigator item's own texture.
     *
     * <p>Not a second copy of the art: the phone icon and the item in the player's hand being the
     * same picture is the point, and a separate asset would be one more thing to keep in step.
     */
    private static final ResourceLocation ICON =
            ResourceLocation.fromNamespaceAndPath("howtogo", "textures/item/navigator.png");

    /**
     * Instantiated by {@link java.util.ServiceLoader}, which needs a public no-argument constructor.
     *
     * <p>Implicit here, and written out in this comment so that nobody later "tidies" it into a
     * private one: a private constructor silently stops the app from existing at all, with no error
     * anywhere, which is the worst possible failure for this class.
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

    /**
     * Never called for this app.
     *
     * <p>{@link #openPage()} is what MCphone uses, because {@link #opensInsidePhone()} is left at its
     * default of true: the whole point of this app is the page drawn inside the phone, so there is
     * nothing to open outside it.
     */
    @Override
    public void onPress() {
    }

    @Override
    public IPhonePage openPage() {
        return new DestinationPickerPage();
    }

    /**
     * Listed without going through the store.
     *
     * <p>There is nothing to buy and nothing to install: the app exists exactly when HowToGo does, so
     * making the player find it in a shop would be a step that can only go wrong.
     */
    @Override
    public boolean isPreinstalled() {
        return true;
    }

    /**
     * Always available, because this class cannot be loaded unless the mod is present.
     *
     * <p>Stated rather than left to a default that might check the mod list: the check has already
     * happened, by the ServiceLoader lookup itself.
     */
    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getVersion() {
        // Read from the mod container rather than a constant, so the number the phone shows and the
        // number in the jar cannot drift apart.
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
     * Nothing to declare.
     *
     * <p>Deliberately left at the default rather than listing HowToGo itself: this app is discovered
     * through HowToGo's own jar, so a requirement on HowToGo would be a check that has already passed
     * and can only produce a false negative.
     */
    @Override
    public List<com.november.mcphone.api.client.app.RequiredMod> requiredMods() {
        return List.of();
    }
}
