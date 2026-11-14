package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import bili.dongsz.howtogo.road.RoadSegment;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
// 1.21.9 made key input a record carrying the code and the scancode together.
//? if >=1.21.9 {
/*import net.minecraft.client.input.KeyEvent;
*///?}
//? if fabric {
/*import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
*///?} else {
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
//?}
// GLFW key constants are named only before 1.21.9; from then on the SDL-backed InputConstants carries them.
//? if <1.21.9 {
import org.lwjgl.glfw.GLFW;
//?}

/**
 * Mouse and keyboard control for the road editor.
 * Keys arrive through ScreenEvent KeyPressed/KeyReleased Pre, ahead of the screen and cancelled only when the editor uses them; mouse clicks arrive through InputEvent MouseButton (Fabric: MouseHandlerMixin), because Xaero's map consumes its keys before InputEvent Key fires.
 * Controls: K opens the mod's main screen (client tick, no screen open); R toggles editing; left click places a point (Alt: no snapping, Shift: select whole road, double Shift: one segment); Ctrl+left sets the navigation destination; right click finishes a road or clears the selection; right drag pans the map when nothing is being drawn; Shift+right toggles a road in the multi-selection; comma/period change road class; minus/equals change storey; N names; O cycles one-way; Delete deletes; Ctrl+Z/Y undo/redo.
 */
public final class RoadEditHandler {

    // 1.21.9 turned the key-mapping category into a record keyed by an Identifier, so its lang key is key.category.howtogo.main.
    //? if >=1.21.9 {
    /*public static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("howtogo", "main"));
    *///?} else {
    public static final String KEY_CATEGORY = "category.howtogo";
    //?}

    /**
     * The keypad minus. 1.21.9 has no named constant for it: 86 is what SDL reports and what GLFW_KEY_KP_SUBTRACT resolved to.
     */
    private static final int KEY_KP_SUBTRACT = 86;

    public static final KeyMapping TOGGLE_EDIT = new KeyMapping(
            "key.howtogo.toggle_edit",
            // 26.1 narrowed the key-type enum and renamed KEYSYM to KEYBOARD; same codes, same meaning.
            //? if >=26.3 {
            /*InputConstants.Type.KEYBOARD,
            *///?} else {
            InputConstants.Type.KEYSYM,
            //?}
            // 1.21.9 reads key codes from the SDL-backed platform layer; InputConstants carries the same values as the old GLFW constants.
            //? if >=1.21.9 {
            /*InputConstants.KEY_R,
            *///?} else {
            GLFW.GLFW_KEY_R,
            //?}
            KEY_CATEGORY);

    /**
     * Opens the line editor. A registered mapping rather than a key code compared in the handler, so it appears in the controls list and can be rebound.
     */
    public static final KeyMapping LINES = new KeyMapping(
            "key.howtogo.lines",
            //? if >=26.3 {
            /*InputConstants.Type.KEYBOARD,
            *///?} else {
            InputConstants.Type.KEYSYM,
            //?}
            //? if >=1.21.9 {
            /*InputConstants.KEY_L,
            *///?} else {
            GLFW.GLFW_KEY_L,
            //?}
            KEY_CATEGORY);

    /**
     * Opens the mod's main screen in the world. Registered so it appears in Controls, and consumed from the client tick because it is a key for the world: it opens only with no screen up.
     */
    public static final KeyMapping MAIN_MENU = new KeyMapping(
            "key.howtogo.main_menu",
            //? if >=26.3 {
            /*InputConstants.Type.KEYBOARD,
            *///?} else {
            InputConstants.Type.KEYSYM,
            //?}
            //? if >=1.21.9 {
            /*InputConstants.KEY_K,
            *///?} else {
            GLFW.GLFW_KEY_K,
            //?}
            KEY_CATEGORY);

    private RoadEditHandler() {
    }

    // NeoForge registers mappings from an event; Fabric's KeyBindingHelper registers them on the spot, called once from the client initialiser.
    //? if fabric {
    /*public static void registerKeys() {
        KeyBindingHelper.registerKeyBinding(TOGGLE_EDIT);
        KeyBindingHelper.registerKeyBinding(LINES);
        // K is the mod's only way into its screens now that it registers no item, so it is offered
        // on both loaders rather than left to whichever one happened to get a handler first.
        KeyBindingHelper.registerKeyBinding(MAIN_MENU);
    }
    *///?} else {
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_EDIT);
        event.register(LINES);
        event.register(MAIN_MENU);
    }

    /**
     * Watches for the world map being closed, so the editing mode it owns does not outlive it. The map screen event fires whenever it is replaced, its editor's panels included, so the session decides on the next tick from what the screen is now.
     */
    public static void onScreenOpening(ScreenEvent.Opening event) {
        // getScreen() is the screen about to open; the one being replaced is getCurrentScreen().
        if (RoadEditSession.isActive() && isMapScreen(event.getCurrentScreen())) {
            RoadEditSession.noteMapScreenClosing();
        }
    }
    //?}

    /**
     * Consumes the main screen's key from the client tick, where a KeyMapping may be read: with no screen open the game's handler records the press and nothing else looks at it. The guard keeps the screen over the world only -- not the map, not chat, not the title screen.
     */
    public static void tickMainMenuKey() {
        Minecraft minecraft = Minecraft.getInstance();
        while (MAIN_MENU.consumeClick()) {
            if (Screens.current() == null && minecraft.level != null && minecraft.player != null) {
                Screens.set(new MainScreen());
            }
        }
    }

    // Mouse, in the two shapes the loaders offer.

    // NeoForge posts InputEvent MouseButton ahead of any screen; Fabric has no global mouse event, so MouseHandlerMixin recreates it. mousePress answers whether the editor used the click, which NeoForge turns into a cancellation and the Mixin into its own return value.
    //? if fabric {
    /*public static boolean onMouseButtonPre(int button, int action) {
        return action == InputConstants.PRESS && mousePress(button);
    }

    public static void onMouseButtonPost(int button, int action) {
        if (action == InputConstants.RELEASE) {
            mouseRelease(button);
        }
    }
    *///?} else {
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getAction() == InputConstants.PRESS && mousePress(event.getButton())) {
            event.setCanceled(true);
        }
    }

    public static void onMouseButtonReleased(InputEvent.MouseButton.Post event) {
        if (event.getAction() == InputConstants.RELEASE) {
            mouseRelease(event.getButton());
        }
    }
    //?}

    private static boolean mousePress(int button) {
        Screen screen = Screens.current();

        // Picking a destination comes before the typing gate: a click is not a typed character, so a focused text field -- the map's search box most of the time -- must not swallow it. The map's own switches are handled by MapFilterOverlay.
        if (button == InputConstants.MOUSE_BUTTON_LEFT && isMapScreen(screen)
                && !RoadEditSession.isActive() && Screen.hasControlDown()) {
            safely("pick destination on map", RoadEditSession::navigateToCursor);
            return true;
        }

        // And while editing, the same click is a destination too: points on a bare click, destination on Ctrl.
        if (isMapOpen(screen) && button == InputConstants.MOUSE_BUTTON_LEFT
                && RoadEditSession.isActive() && Screen.hasControlDown()) {
            safely("pick destination on map", RoadEditSession::navigateToCursor);
            return true;
        }

        if (!isMapOpen(screen)) {
            return false;
        }
        if (!RoadEditSession.isActive()) {
            return false;
        }

        switch (button) {
            case InputConstants.MOUSE_BUTTON_LEFT -> {
                if (Screen.hasShiftDown()) {
                    safely("begin drag", RoadEditSession::beginDragAtCursor);
                } else {
                    safely("place point", RoadEditSession::clickPlace);
                }
                return true;
            }
            case InputConstants.MOUSE_BUTTON_RIGHT -> {
                if (RoadEditSession.isDrawing()) {
                    // A line is on the map -- the purple rubber band -- so this is the gesture that ends it.
                    safely("finish road", RoadEditSession::clickFinishOrClear);
                } else {
                    // Nothing is being drawn, so the button is free and it drags the map instead. The press is still taken from Xaero: the pan is Xaero's own, driven by a left press this session hands it in beginMapPan, and letting the real right click through would open the map's right-click menu. Shift is read here because the handler owns the input state of the press.
                    safely("pan map", () -> RoadEditSession.beginMapPan(Screen.hasShiftDown()));
                }
                return true;
            }
            default -> {
                // Middle click and friends stay with Xaero.
            }
        }
        return false;
    }

    private static void mouseRelease(int button) {
        if (button == InputConstants.MOUSE_BUTTON_LEFT
                && RoadEditSession.draggingNodeId() != RoadSegment.NO_NODE) {
            RoadEditSession.endDrag();
        }
        if (button == InputConstants.MOUSE_BUTTON_RIGHT && RoadEditSession.isPanningMap()) {
            safely("end map pan", RoadEditSession::endMapPan);
        }
    }

    // --------------------------------------------------------------- keyboard

    // The key numbers behind a screen key event, per version. 26.1 renamed the event's second component to keycode(). Pulled out here so the handlers stay free of conditions: they sit inside a loader condition, and Stonecutter's scopes do not nest.
    //? if neoforge && >=26.3 {
    /*private static int keyOf(ScreenEvent.KeyPressed.Pre event) {
        return event.getKeyEvent().key();
    }

    private static int scanOf(ScreenEvent.KeyPressed.Pre event) {
        return event.getKeyEvent().keycode();
    }

    private static int keyOf(ScreenEvent.KeyReleased.Pre event) {
        return event.getKeyEvent().key();
    }

    private static int scanOf(ScreenEvent.KeyReleased.Pre event) {
        return event.getKeyEvent().keycode();
    }
    *///?}
    //? if neoforge && <26.3 {
    private static int keyOf(ScreenEvent.KeyPressed.Pre event) {
        return event.getKeyCode();
    }

    private static int scanOf(ScreenEvent.KeyPressed.Pre event) {
        return event.getScanCode();
    }

    private static int keyOf(ScreenEvent.KeyReleased.Pre event) {
        return event.getKeyCode();
    }

    private static int scanOf(ScreenEvent.KeyReleased.Pre event) {
        return event.getScanCode();
    }
    //?}

    // Keys, in the two shapes the loaders offer. Both are handled ahead of the screen and both answer whether the screen may see the key: NeoForge cancels the event, Fabric returns false. The dispatch is shared; on Fabric the numbers arrive as plain ints.
    //? if fabric && <1.21.9 {
    /*public static void registerScreenHandlers() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!isXaeroScreen(screen)) {
                return;
            }
            ScreenKeyboardEvents.allowKeyPress(screen).register((open, keyCode, scanCode, modifiers) ->
                    !(isMapOpen(open) && handleKey(open, keyCode, scanCode, InputConstants.PRESS)));
            ScreenKeyboardEvents.allowKeyRelease(screen).register((open, keyCode, scanCode, modifiers) ->
                    !(isMapOpen(open) && handleKey(open, keyCode, scanCode, InputConstants.RELEASE)));
        });
    }
    *///?}
    // The same registration from 1.21.9, where the key callbacks take a KeyEvent -- two methods for the same reason as MapFilterOverlay register().
    //? if fabric && >=1.21.9 {
    /*public static void registerScreenHandlers() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!isXaeroScreen(screen)) {
                return;
            }
            ScreenKeyboardEvents.allowKeyPress(screen).register((open, event) ->
                    !(isMapOpen(open) && handleKey(open, event.key(), event.scancode(), InputConstants.PRESS)));
            ScreenKeyboardEvents.allowKeyRelease(screen).register((open, event) ->
                    !(isMapOpen(open) && handleKey(open, event.key(), event.scancode(), InputConstants.RELEASE)));
        });
    }
    *///?}
    //? if neoforge {
    // A key was pressed while some screen is open: the editor's only way in.
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (isMapOpen(event.getScreen())
                && handleKey(event.getScreen(), keyOf(event), scanOf(event), InputConstants.PRESS)) {
            event.setCanceled(true);
        }
    }

    // A key was released: only a shift release matters, and it ends a drag.
    public static void onScreenKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (isMapOpen(event.getScreen())
                && handleKey(event.getScreen(), keyOf(event), scanOf(event), InputConstants.RELEASE)) {
            event.setCanceled(true);
        }
    }
    //?}

    /**
     * Runs one key against the editor. Returns whether the editor used it, which decides if the screen is allowed to see it.
     */
    private static boolean handleKey(Screen screen, int key, int scanCode, int action) {
        if (key == InputConstants.KEY_LSHIFT || key == InputConstants.KEY_RSHIFT) {
            if (action == InputConstants.RELEASE) {
                RoadEditSession.endDrag();
                return true;
            }
            return false;
        }

        if (action != InputConstants.PRESS) {
            return false;
        }

        // 1.21.9 made KeyMapping matches take the key event record, so it is built here. The dispatch keeps int parameters because it compares the key against a dozen constants, and a condition at each would be a dozen chances to invert one.
        //? if >=1.21.9 {
        /*if (TOGGLE_EDIT.matches(new KeyEvent(key, scanCode, 0))) {
        *///?} else {
        if (TOGGLE_EDIT.matches(key, scanCode)) {
        //?}
            RoadEditSession.toggle();
            HowToGo.LOGGER.info("[HowToGo] road editing {}",
                    RoadEditSession.isActive() ? "enabled" : "disabled");
            return true;
        }
        if (!RoadEditSession.isActive()) {
            return false;
        }

        boolean ctrl = Screen.hasControlDown();
        if (ctrl && key == InputConstants.KEY_Z) {
            safely("undo", RoadEditSession::undo);
        } else if (ctrl && key == InputConstants.KEY_Y) {
            safely("redo", RoadEditSession::redo);
        } else if (key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE) {
            safely("delete", RoadEditSession::deleteSelected);
        } else if (key == InputConstants.KEY_COMMA) {
            // '<' on most layouts; '[' and ']' are taken by Xaero's own map shortcuts.
            safely("change class", () -> RoadEditSession.cycleActiveClass(-1));
        } else if (key == InputConstants.KEY_PERIOD) {
            // '>'
            safely("change class", () -> RoadEditSession.cycleActiveClass(1));
        } else if (key == InputConstants.KEY_MINUS || key == KEY_KP_SUBTRACT) {
            // '-' and its numpad twin: one storey down. The keypad is included because the two keys are the same idea.
            safely("lower storey", () -> RoadEditSession.stepActiveLayer(-1));
        } else if (key == InputConstants.KEY_EQUALS || key == InputConstants.KEY_ADD) {
            // '=' -- the key most layouts write '+' on when shifted -- and the numpad plus. Both act on the unshifted press, so + and = are one binding.
            safely("raise storey", () -> RoadEditSession.stepActiveLayer(1));
        } else if (key == InputConstants.KEY_N) {
            safely("name road", RoadEditSession::nameSelectedRoad);
        } else if (key == InputConstants.KEY_P) {
            safely("place landmark", RoadEditSession::placePoi);
        } else if (key == InputConstants.KEY_O) {
            // One-way. A bare letter, not a registered mapping, because it acts on a selection the editor owns and exists only while editing.
            safely("toggle one-way", RoadEditSession::toggleOneWay);
        //? if >=1.21.9 {
        /*} else if (LINES.matches(new KeyEvent(key, scanCode, 0))) {
        *///?} else {
        } else if (LINES.matches(key, scanCode)) {
        //?}
            // The lines are a property of the network rather than of whatever is selected, so this opens on the map itself and needs no selection first. Deferred to the next tick like every screen here.
            safely("edit lines", RoadEditSession::promptLineEditor);
        } else {
            return false;
        }
        return true;
    }

    /**
     * Runs an interaction, logging instead of propagating. These handlers run deep inside the input dispatch chain, so an escaping exception takes the whole game down.
     */
    private static void safely(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            HowToGo.LOGGER.error("[HowToGo] '{}' failed", what, t);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Whether the player is looking at Xaero's world map, the only place the editor is offered.
     * Not a list of class names: the test uses evidence that does not depend on a name -- the screen or something it extends is declared in xaero.map.* (the minimap's xaero.common.* is not), and failing that the screen is Xaero's and MapViewState is fresh, since that is only updated from the map's render pass.
     * False with no screen open, and false when a focused widget is a text field, so a letter typed into a name or search box is text before it is a shortcut.
     */
    private static boolean isMapOpen(Screen screen) {
        if (screen == null || isTyping(screen)) {
            return false;
        }
        return isMapScreen(screen);
    }

    /**
     * Whether the screen belongs to Xaero's world map mod at all, without asking whether the map is being drawn.
     * The registration-time question, deliberately weaker than isMapScreen: registration happens in ScreenEvents AFTER_INIT, before the screen has rendered a frame, and freshness is evidence only the render pass can supply. Asking it there answered no for every freshly opened map -- which is why R and L did nothing on Fabric while NeoForge, which subscribes globally, worked. The per-key gate still goes through isMapScreen.
     */
    static boolean isXaeroScreen(Screen screen) {
        return screen != null && isInPackage(screen, "xaero.");
    }

    /**
     * Whether the screen is the world map's, with no opinion about what is focused. The distinction exists for the mouse: a focused text field must swallow shortcut keys, but must not swallow a click on the mod's own panel or a click that picks a destination.
     */
    static boolean isMapScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        // Two things, not one: the screen has to be Xaero's, and the map has to have drawn in the last moment -- freshness tells the map apart from that mod's other screens. The package alone held export, the teleport commands, map naming, world switching and settings, where Delete deleted the selected road, N renamed it and the display panel drew over their corner.
        return isInPackage(screen, "xaero.") && MapViewState.isFresh();
    }

    /** Whether the screen, or anything it extends, is declared in the given package. */
    private static boolean isInPackage(Screen screen, String prefix) {
        for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getName();
            int lastDot = name.lastIndexOf('.');
            if (lastDot > 0 && name.substring(0, lastDot + 1).startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a text field has the focus, so the next letter is text rather than a shortcut. Asked of the screen's own focus, because every field the game builds reports itself there.
     */
    private static boolean isTyping(Screen screen) {
        return screen.getFocused() instanceof EditBox;
    }

}
