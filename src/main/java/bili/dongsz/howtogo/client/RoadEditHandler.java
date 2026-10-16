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
// The GLFW key codes are named only on the pre-1.21.9 nodes; from 1.21.9 on the SDL-backed
// InputConstants carries them and this import would go unused.
//? if <1.21.9 {
import org.lwjgl.glfw.GLFW;
//?}

/**
 * Mouse and keyboard control for the road editor.
 *
 * <h2>Why the keys come through the screen event and the mouse does not</h2>
 * The two input paths are not symmetric in this NeoForge build, and the asymmetry is the whole
 * reason the editor's keys stopped working while its mouse kept going. Read out of the patched
 * sources:
 * <ul>
 *   <li>{@code KeyboardHandler.keyPress} offers the key to the open screen <b>first</b>
 *       ({@code onScreenKeyPressedPre}, then {@code screen.keyPressed}) and returns right there if the
 *       screen took it, posting {@code InputEvent.Key} only afterwards. Xaero's map consumes the keys
 *       it uses, so an {@code InputEvent.Key} listener never sees them: the R press was being eaten by
 *       the map screen before NeoForge's key event existed at all, which no gate could fix.</li>
 *   <li>{@code MouseHandler.onPress} posts {@code InputEvent.MouseButton} <b>before</b> any screen
 *       dispatch, so clicks arrive whether the screen wants them or not. That is why editing by mouse
 *       always worked.</li>
 * </ul>
 * So keys are handled from {@link ScreenEvent.KeyPressed.Pre} and {@link ScreenEvent.KeyReleased.Pre},
 * which fire before the screen sees the key and are indifferent to whether it would consume it, and
 * the key is cancelled only when the editor actually uses it -- otherwise Xaero would lose its own
 * shortcuts. There is deliberately no {@code InputEvent.Key} listener beside these: a key the map
 * happens not to consume would then be handled twice, and R would toggle on and off again.
 *
 * <h2>Controls</h2>
 * <ul>
 *   <li><b>R</b> - toggle road editing (also rebindable under Controls)</li>
 *   <li><b>Left click</b> - place a point, extending the road being drawn</li>
 *   <li><b>Alt + left click</b> - place a point exactly where the cursor is, snapping disabled</li>
 *   <li><b>Shift + left click / drag</b> - grab and move a node, or select a road</li>
 *   <li><b>Ctrl + left click</b> - set the current cursor spot as the navigation destination
 *       (works whether or not editing is on). This is the only way to start navigating from the map;
 *       there used to be a bare {@code G} as well, and it was dropped because a single unmodified
 *       letter on the map is too easy to press by accident and the map already owns most of them.</li>
 *   <li><b>Right click</b> - with a road in hand, finish it; with nothing in hand, clear the selection</li>
 *   <li><b>Right drag</b> - drag the map. The right button is the road's while a road is being drawn,
 *       and the map's otherwise: with nothing in hand there is no line to finish, and a map that cannot
 *       be moved is a map you cannot draw on. See {@link RoadEditSession#beginMapPan}</li>
 *   <li><b>&lt; / &gt;</b> (comma / period) - change the road class being drawn, or of the selection</li>
 *   <li><b>- / +</b> (minus / equals, and the keypad) - the storey new roads are drawn on: down one,
 *       up one. Shown in the readout beside the statistics, which is where the class being drawn is
 *       shown, since both are "what the next click will be"</li>
 *   <li><b>N</b> - name the selected road, or the road under the cursor</li>
 *   <li><b>O</b> - make the selected road, or the road under the cursor, one-way; each press moves it
 *       on to the next state, and the fourth brings it back to two-way</li>
 *   <li><b>Delete</b> - delete the selection</li>
 *   <li><b>Ctrl+Z / Ctrl+Y</b> - undo / redo</li>
 * </ul>
 */
public final class RoadEditHandler {

    // 1.21.9 turned the key-mapping category into a record keyed by an Identifier: it names itself
    // and its label resolves from that id, which is why the lang key is key.category.howtogo.main.
    //? if >=1.21.9 {
    /*public static final KeyMapping.Category KEY_CATEGORY = new KeyMapping.Category(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("howtogo", "main"));
    *///?} else {
    public static final String KEY_CATEGORY = "category.howtogo";
    //?}

    /**
     * The keypad minus.
     *
     * <p>1.21.9 has no named constant for it: the SDL-backed {@code InputConstants} covers the
     * main-row keys this file uses, but the keypad's own codes are spelled out. 86 is what SDL
     * reports for keypad minus, and what {@code GLFW_KEY_KP_SUBTRACT} resolved to.
     */
    private static final int KEY_KP_SUBTRACT = 86;

    public static final KeyMapping TOGGLE_EDIT = new KeyMapping(
            "key.howtogo.toggle_edit",
            // 26.1 narrowed the key-type enum to two members and renamed the one for ordinary keys:
            // what was KEYSYM is KEYBOARD. Same codes, same meaning.
            //? if >=26.3 {
            /*InputConstants.Type.KEYBOARD,
            *///?} else {
            InputConstants.Type.KEYSYM,
            //?}
            // 1.21.9 reads key codes from the SDL-backed platform layer, so the GLFW constants this
            // used to name are gone; InputConstants carries the same values.
            //? if >=1.21.9 {
            /*InputConstants.KEY_R,
            *///?} else {
            GLFW.GLFW_KEY_R,
            //?}
            KEY_CATEGORY);

    /**
     * Opens the line editor.
     *
     * <p>A registered mapping rather than a key code compared in the handler, so that it appears in
     * the controls list and can be rebound. A key that exists only in the code is a key nobody can
     * find, which is what the first version of this was.
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

    private RoadEditHandler() {
    }

    // NeoForge registers mappings from an event; Fabric's KeyBindingHelper registers them on the
    // spot, called once from the client initialiser.
    //? if fabric {
    /*public static void registerKeys() {
        KeyBindingHelper.registerKeyBinding(TOGGLE_EDIT);
        KeyBindingHelper.registerKeyBinding(LINES);
    }
    *///?} else {
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_EDIT);
        event.register(LINES);
    }
    //?}

    // ------------------------------------------------------------------ mouse

    // Mouse, in the two shapes the loaders offer.
    //
    // NeoForge posts InputEvent.MouseButton.Pre/Post from MouseHandler.onPress, ahead of any screen
    // seeing the click. Fabric has no global mouse event at all, so that vantage point is recreated
    // by MouseHandlerMixin, which calls the two entry points below from the same method NeoForge
    // posts from. The bodies are shared: mousePress answers whether the editor used the click, which
    // the NeoForge adapter turns into a cancellation and the Mixin into its own return value.
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

        // Picking a destination comes before the typing gate: a click is not a typed character, so a
        // focused text field -- the map's search box, most of the time -- must not swallow it. With that
        // gate in front, a player who had once clicked in the search box found the map unpickable.
        //
        // The map's own switches are not handled here at all: they belong to the screen rather than to
        // the map, and are handled and drawn by MapFilterOverlay.
        if (button == InputConstants.MOUSE_BUTTON_LEFT && isMapScreen(screen)
                && !RoadEditSession.isActive() && Screen.hasControlDown()) {
            safely("pick destination on map", RoadEditSession::navigateToCursor);
            return true;
        }

        // And while editing, the same click is a destination too: the editor places points on a bare
        // click and picks a destination on a Ctrl one.
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
                    // A line is on the map -- the purple rubber band -- so this is the gesture that ends
                    // it, exactly as it always was.
                    safely("finish road", RoadEditSession::clickFinishOrClear);
                } else {
                    // Nothing is being drawn, so the button is free and it drags the map instead. The
                    // press is still taken from Xaero: the pan is Xaero's own, driven by a left press this
                    // session hands it in beginMapPan, and letting the real right click through as well
                    // would open the map's right-click menu on the release. See RoadEditSession.
                    safely("pan map", RoadEditSession::beginMapPan);
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

    // The key numbers behind a screen key event, per version.
    //
    // 26.1 renamed the key event's second component: what was scancode() is keycode() there. Pulling
    // the two out here rather than inside the handlers below is not tidiness -- it is what keeps
    // those handlers free of conditions, and they have to be, because they sit inside a loader
    // condition and Stonecutter's scopes do not nest. A version condition inside a loader condition
    // would close it and leave the handler live on the wrong loader.
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

    // Keys, in the two shapes the loaders offer.
    //
    // Both are handled from ahead of the screen, and both answer the same question: may the screen
    // see this key? NeoForge answers by cancelling the event, Fabric by returning false from the
    // per-screen callback -- so the dispatch itself, handleKey, is shared and only the entry points
    // differ. The map screen keeps every shortcut the editor does not claim either way.
    //
    // On Fabric the two numbers arrive as plain ints, so the scancode/keycode rename the accessors
    // above exist for does not reach this side at all.
    //? if fabric {
    /*public static void registerScreenHandlers() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!isMapScreen(screen)) {
                return;
            }
            ScreenKeyboardEvents.allowKeyPress(screen).register((open, keyCode, scanCode, modifiers) ->
                    !(isMapOpen(open) && handleKey(open, keyCode, scanCode, InputConstants.PRESS)));
            ScreenKeyboardEvents.allowKeyRelease(screen).register((open, keyCode, scanCode, modifiers) ->
                    !(isMapOpen(open) && handleKey(open, keyCode, scanCode, InputConstants.RELEASE)));
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
     * Runs one key against the editor.
     *
     * @return whether the editor used it, which is what decides if the screen is allowed to see it
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

        // 1.21.9 made KeyMapping.matches take the key event record rather than the code and the
        // scancode separately, so the record is built here. The dispatch keeps its int parameters
        // because it compares the key against a dozen constants as well as the two mappings, and a
        // condition at each of those would be a dozen chances to invert one.
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
            // '-' and its numpad twin: one storey down. The keypad is included because the two keys are
            // the same idea and a player on a full keyboard reaches for whichever is nearer.
            safely("lower storey", () -> RoadEditSession.stepActiveLayer(-1));
        } else if (key == InputConstants.KEY_EQUALS || key == InputConstants.KEY_ADD) {
            // '=' -- which is also the '+' most layouts write on the shifted key -- and the numpad plus.
            // Both act on the press of the unshifted key, so '+' and '=' are one binding rather than two
            // that a player has to guess between.
            safely("raise storey", () -> RoadEditSession.stepActiveLayer(1));
        } else if (key == InputConstants.KEY_N) {
            safely("name road", RoadEditSession::nameSelectedRoad);
        } else if (key == InputConstants.KEY_P) {
            safely("place landmark", RoadEditSession::placePoi);
        } else if (key == InputConstants.KEY_O) {
            // One-way. A bare letter like N and P, and not a registered mapping, for the same reason
            // those are not: it acts on a selection the editor owns and only exists while editing is on.
            safely("toggle one-way", RoadEditSession::toggleOneWay);
        //? if >=1.21.9 {
        /*} else if (LINES.matches(new KeyEvent(key, scanCode, 0))) {
        *///?} else {
        } else if (LINES.matches(key, scanCode)) {
        //?}
            // The lines are a property of the network rather than of whatever is selected, so this
            // opens on the map itself and needs no selection first. Deferred to the next tick, like
            // every other screen here: opening one from the input handler is what makes a key look
            // dead.
            safely("edit lines", RoadEditSession::promptLineEditor);
        } else {
            return false;
        }
        return true;
    }

    /**
     * Runs an interaction, logging instead of propagating.
     *
     * <p>These handlers run deep inside the input dispatch chain, so an escaping exception takes
     * the whole game down -- a bug in the editor should cost the player a log line, not their
     * session.
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
     * Whether the player is looking at Xaero's world map, which is the only place the editor is
     * offered.
     *
     * <h2>Why this is not a list of class names</h2>
     * It used to be: the screen's superclass chain had to contain exactly {@code xaero.map.gui.GuiMap}.
     * That is a name, and a name is the one thing an update to Xaero is free to change -- the map
     * simply stopped being recognised, and with it every editor key. The test is now built on two
     * pieces of evidence that do not depend on a name:
     * <ol>
     *   <li>the screen, or something it extends, is declared in the World Map's own packages
     *       ({@code xaero.map.*}). This is a whole package rather than one class, so a renamed or
     *       newly subclassed map screen is still recognised, while the Minimap's own screens
     *       ({@code xaero.common.*}) are not;</li>
     *   <li>failing that, the screen is one of Xaero's and <b>Xaero is drawing the map behind it right
     *       now</b> -- {@link MapViewState} is only updated from the map's render pass, so a fresh
     *       reading is direct evidence that the map is on screen, whatever its class is called.</li>
     * </ol>
     *
     * <p>Two things keep this from opening the gate where it should not. With no screen open it is
     * false, so nothing here works in the world -- as nothing ever did. And a screen whose focused
     * widget is a text field is false too, so a letter typed into a name or search box is text before
     * it is a shortcut, whether that field is ours or someone else's.
     */
    private static boolean isMapOpen(Screen screen) {
        if (screen == null || isTyping(screen)) {
            return false;
        }
        return isMapScreen(screen);
    }

    /**
     * Whether the screen is the world map's, with no opinion about what is focused.
     *
     * <p>The distinction exists for the mouse: a focused text field has to swallow shortcut <em>keys</em>,
     * because a letter typed into a search box is text, but it must not swallow a click on the mod's own
     * panel or a click that picks a destination.
     */
    static boolean isMapScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        // Two things, not one. The screen has to be Xaero's, and the map has to have drawn something
        // in the last moment -- and the second is what tells the map apart from the rest of that mod's
        // screens. The package alone was the whole test, and that package holds more than the map:
        // export, the teleport commands, naming a map, world switching, settings. With editing on,
        // Delete deleted the selected road from those screens and N renamed it, with the key swallowed
        // so the screen itself never saw it, and the display panel drew itself over their corner and
        // ate the clicks that landed on it. Freshness of the view state is evidence that the map is
        // what is being looked at, and it is evidence this class already had.
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
     * Whether a text field has the focus, so the next letter is text rather than a shortcut.
     *
     * <p>Asked of the screen's own focus rather than of the screen's class, because that is the fact
     * that matters: every field the game builds reports itself here, ours and Xaero's alike.
     */
    private static boolean isTyping(Screen screen) {
        return screen.getFocused() instanceof EditBox;
    }

}
