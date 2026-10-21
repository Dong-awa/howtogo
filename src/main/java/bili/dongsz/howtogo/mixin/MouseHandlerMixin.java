// Gives the road editor the raw mouse clicks Fabric does not offer.
//
// WHY THIS IS THE ONE MIXIN IN THE PORT
//
// On NeoForge the editor's clicks come from InputEvent.MouseButton.Pre/Post, which
// MouseHandler posts BEFORE any screen is given the click -- exactly what the editor needs,
// because a click the map screen would have swallowed must still reach it. Fabric exposes
// screen-scoped mouse events only (ScreenMouseEvents, which fire once the screen owns the click and
// cannot cancel a click the screen has already decided about in the way this mod needs), so the same
// vantage point has to be recreated at the source.
//
// The handler covers both the press and the release: GLFW reports both through the same callback and
// the action is one of its arguments, so intercepting the head and the return of this one method
// covers both events NeoForge posted. The descriptor form of the target is used rather than the bare
// name because the method is private, and getting it wrong by signature fails at load rather than at
// compile time -- which is exactly how 1.21.11 broke this file.
//
// TWO ARMS, AND WHY THE BOUNDARY IS 1.21.11
//
// 1.21.11 split the button and the modifier flags into a record and renamed the method with them:
//
//     1.20.1 .. 1.21.8   private void onPress(long, int button, int action, int modifiers)
//     1.21.11 and later  private void onButton(long, MouseButtonInfo buttonInfo, int action)
//
// Read out of each node's own jar rather than guessed: 1.21.8 is the last node with onPress and
// 1.21.11 the first with onButton, and MouseButtonInfo's accessor is button(). The failure this
// produced was quiet -- the mixin was simply not applied, so the editor got no clicks at all on
// those nodes, and the log line naming the missing target was the only sign of it.
//
// The mixin is declared under `client` in howtogo.mixins.json: MouseHandler does not exist on a
// dedicated server, so it must never even be considered for application there.
//
// Cancelling on a press swallows the click entirely -- the editor's own rule is to cancel only when
// it actually used the click, so that Xaero keeps every click the editor did not claim.
//
// The file is fenced rather than conditioned inside, because there is nothing in it NeoForge wants:
// it has no equivalent of this mixin at all. Every comment is `//` lines rather than a block comment
// for the usual reason -- a javadoc opener inside the fence would close it early.
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
