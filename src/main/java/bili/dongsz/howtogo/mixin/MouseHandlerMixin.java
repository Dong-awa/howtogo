// Gives the road editor the raw mouse clicks Fabric does not expose.
// On NeoForge the editor gets InputEvent.MouseButton.Pre/Post before any screen sees the click;
// Fabric has only screen-scoped events, so the vantage point is recreated at MouseHandler.
// Intercepting head and return covers press and release: GLFW reports both through one callback.
// The two arms, split at 1.21.11, which renamed the method and wrapped button+modifiers:
//   1.20.1 .. 1.21.8   onPress(long, int button, int action, int modifiers)
//   1.21.11 and later  onButton(long, MouseButtonInfo buttonInfo, int action)
// The descriptor form is used because the method is private; a wrong signature fails at load.
// Client only: declared under client in howtogo.mixins.json; absent on a dedicated server.
// Cancel a press only when the editor used it, so Xaero keeps every click it did not claim.
// All comments are // lines because a javadoc opener inside the fence would close it early.
//? if fabric && <1.21.11 {
/*package bili.dongsz.howtogo.mixin;

import bili.dongsz.howtogo.client.RoadEditHandler;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    // The press, before any screen sees it: the equivalent of InputEvent.MouseButton.Pre.
    @Inject(method = "onPress(JIII)V", at = @At("HEAD"), cancellable = true)
    private void howtogo$beforePress(long window, int button, int action, int modifiers,
                                     CallbackInfo info) {
        if (RoadEditHandler.onMouseButtonPre(button, action)) {
            info.cancel();
        }
    }

    // The release, after everything else has run: the equivalent of InputEvent.MouseButton.Post.
    @Inject(method = "onPress(JIII)V", at = @At("RETURN"))
    private void howtogo$afterPress(long window, int button, int action, int modifiers,
                                    CallbackInfo info) {
        RoadEditHandler.onMouseButtonPost(button, action);
    }
}
*///?}
//? if fabric && >=1.21.11 {
/*package bili.dongsz.howtogo.mixin;

import bili.dongsz.howtogo.client.RoadEditHandler;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    // The press, before any screen sees it: the equivalent of InputEvent.MouseButton.Pre.
    // The modifier flags now ride along inside the record; this mod only ever wanted the button.
    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
            at = @At("HEAD"), cancellable = true)
    private void howtogo$beforePress(long window, MouseButtonInfo buttonInfo, int action,
                                     CallbackInfo info) {
        if (RoadEditHandler.onMouseButtonPre(buttonInfo.button(), action)) {
            info.cancel();
        }
    }

    // The release, after everything else has run: the equivalent of InputEvent.MouseButton.Post.
    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
            at = @At("RETURN"))
    private void howtogo$afterPress(long window, MouseButtonInfo buttonInfo, int action,
                                    CallbackInfo info) {
        RoadEditHandler.onMouseButtonPost(buttonInfo.button(), action);
    }
}
*///?}
