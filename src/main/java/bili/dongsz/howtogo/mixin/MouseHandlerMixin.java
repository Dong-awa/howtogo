package bili.dongsz.howtogo.mixin;

import bili.dongsz.howtogo.client.RoadEditHandler;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives the road editor the raw mouse clicks Fabric does not offer.
 *
 * <h2>Why this is the one Mixin in the port</h2>
 * On NeoForge the editor's clicks came from {@code InputEvent.MouseButton.Pre/Post}, which
 * {@code MouseHandler.onPress} posts <em>before</em> any screen is given the click -- which is exactly
 * what the editor needs, because a click the map screen would have swallowed must still reach it. Fabric
 * exposes screen-scoped mouse events only ({@code ScreenMouseEvents}, which fire once the screen owns the
 * click and cannot cancel a click the screen has already decided about in the way this mod needs), so the
 * same vantage point has to be recreated at the source.
 *
 * <p>{@code onPress} handles both the press and the release: GLFW reports both through the same callback
 * and the action is one of its arguments, so intercepting the head and the return of this one method
 * covers both events NeoForge posted. The descriptor form of the target is used rather than the bare name
 * because the method is private and overloaded by nothing but is easy to get wrong by signature.
 *
 * <p>The mixin is declared under {@code client} in {@code howtogo.mixins.json}: {@code MouseHandler} does
 * not exist on a dedicated server, so it must never even be considered for application there.
 *
 * <p>Cancelling on a press swallows the click entirely -- the editor's own rule is to cancel only when it
 * actually used the click, so that Xaero keeps every click the editor did not claim.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    /** The press, before any screen sees it: the equivalent of {@code InputEvent.MouseButton.Pre}. */
    @Inject(method = "onPress(JIII)V", at = @At("HEAD"), cancellable = true)
    private void howtogo$beforePress(long window, int button, int action, int modifiers,
                                     CallbackInfo info) {
        if (RoadEditHandler.onMouseButtonPre(button, action)) {
            info.cancel();
        }
    }

    /** The release, after everything else has run: the equivalent of {@code InputEvent.MouseButton.Post}. */
    @Inject(method = "onPress(JIII)V", at = @At("RETURN"))
    private void howtogo$afterPress(long window, int button, int action, int modifiers,
                                    CallbackInfo info) {
        RoadEditHandler.onMouseButtonPost(button, action);
    }
}
