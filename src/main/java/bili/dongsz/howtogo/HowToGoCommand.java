package bili.dongsz.howtogo;

import bili.dongsz.howtogo.api.SelfChecks;
import bili.dongsz.howtogo.client.DestinationScreen;
import bili.dongsz.howtogo.client.Navigation;
import bili.dongsz.howtogo.client.SelfTest;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.TravelMode;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The mod's own commands: open the terminal, end the trip, switch how you are travelling.
 *
 * <h2>Why these are client commands</h2>
 * Everything they do -- opening a screen, dropping a route, changing a travel mode -- is state this
 * client keeps for itself, and none of it means anything to the server. Registered as client commands
 * they therefore run here, without a round trip, without the server needing this mod, and without
 * needing permission for anything: a player on somebody else's server gets the same three commands a
 * player in their own world does, which is the only version of this that makes sense. On a client the
 * server's dispatcher is not even consulted for a command it does not own.
 *
 * <h2>Why opening the terminal is deferred a tick</h2>
 * A command runs inside the chat screen's own handling of the line that was typed, and by the time it
 * returns that screen is still the one the client thinks is open. Opening another screen from in there
 * is what makes a key or a command look dead: the new screen is set, the old one's close puts the game
 * screen back, and the player is left looking at the world. The work is therefore parked and run on the
 * next client tick, which is the same arrangement the editor uses for the screens it opens from a key.
 */
public final class HowToGoCommand {

    /** The command, and the short form of it. */
    private static final String ROOT = "howtogo";
    private static final String ALIAS = "htg";

    /** Work deferred out of command handling to the next client tick. */
    private static Runnable pending;

    private HowToGoCommand() {
    }

    /**
     * Registers the commands.
     *
     * <p>On the client's own dispatcher, so the tree is rebuilt whenever the client is -- a reconnect,
     * or a change of server -- and never goes stale.
     */
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        // Registered twice rather than redirected: a redirect would put one node in two trees, and the
        // client's dispatcher is rebuilt from scratch each time, so building the tree again costs
        // nothing and keeps the two spellings genuinely independent.
        dispatcher.register(tree(ROOT));
        dispatcher.register(tree(ALIAS));
    }

    /** Runs whatever a command had to leave for the next tick. */
    public static void tick() {
        Runnable action = pending;
        if (action == null) {
            return;
        }
        pending = null;
        try {
            action.run();
        } catch (Throwable t) {
            // This runs in the client tick: a failure here would take the game down, and a screen that
            // would not open is not worth that.
            HowToGo.LOGGER.error("[HowToGo] opening a screen from a command failed", t);
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tree(String name) {
        return Commands.literal(name)
                .executes(HowToGoCommand::help)
                .then(Commands.literal("terminal")
                        .executes(HowToGoCommand::terminal))
                .then(Commands.literal("stop")
                        .executes(HowToGoCommand::stop))
                .then(Commands.literal("selftest")
                        .executes(HowToGoCommand::selfTest))
                .then(Commands.literal("mode")
                        .executes(HowToGoCommand::cycleMode)
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    for (TravelMode mode : TravelMode.values()) {
                                        builder.suggest(mode.id());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(HowToGoCommand::setMode)));
    }

    /** Says what the command does, rather than doing something surprising with no arguments. */
    private static int help(CommandContext<CommandSourceStack> context) {
        say(context, Component.translatable("command.howtogo.help", "/" + ROOT));
        return 1;
    }

    /**
     * Opens the destination picker, which is the terminal the navigator item opens.
     *
     * <p>The parent screen is asked for on the tick the screen is built and not here, so that whatever
     * the player is looking at when the command runs is the screen it comes back to.
     */
    private static int terminal(CommandContext<CommandSourceStack> context) {
        pending = () -> {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.setScreen(new DestinationScreen(minecraft.screen));
        };
        say(context, Component.translatable("command.howtogo.terminal"));
        return 1;
    }

    /**
     * Runs the mod's own checks against the world that is open, and reports what they found.
     *
     * <p>The one test that has to run in the game: whether the roads the player drew are the roads the
     * mod loaded, whether their lines can carry a journey across the world as it stands, and what the
     * guidance would actually say on the way. It sets a destination and puts back the one being
     * navigated, which is why this says so before it runs -- see {@link SelfTest}.
     *
     * <p>The report goes to the log in full and to chat one line at a time, so a finding can be read
     * without alt-tabbing and quoted from the log afterwards.
     *
     * <p>Checks contributed by other mods run after this mod's own and land in the same report, in the
     * same shape: whether what is in this world is what the mod thinks is in it is the same question
     * for a source another mod contributed, and the player asking it is already here.
     */
    private static int selfTest(CommandContext<CommandSourceStack> context) {
        List<String> lines;
        int failed = 0;
        try {
            List<SelfTest.Result> results = new ArrayList<>(SelfTest.run());
            for (SelfChecks.Outcome outcome : SelfChecks.runAll()) {
                results.add(new SelfTest.Result(outcome.name(), outcome.ok(), outcome.detail()));
            }
            lines = new ArrayList<>(results.size() + 2);
            for (SelfTest.Result result : results) {
                if (!result.ok()) {
                    failed++;
                }
                HowToGo.LOGGER.info("[HowToGo] selftest | {} | {} | {}", result.ok() ? "ok" : "FAIL",
                        result.name(), result.detail());
                lines.add(Component.translatable(
                                result.ok() ? "command.howtogo.selftest.ok" : "command.howtogo.selftest.fail",
                                Component.translatable(result.name()), result.detail())
                        .getString());
            }
        } catch (RuntimeException | LinkageError broke) {
            // A check that throws is itself the answer, and the one thing that must not happen is the
            // command dying with it: the stack is written down and reported as a failure.
            HowToGo.LOGGER.error("[HowToGo] selftest could not run", broke);
            say(context, Component.translatable("command.howtogo.selftest.broken", String.valueOf(broke)));
            return 0;
        }
        say(context, Component.translatable("command.howtogo.selftest.header", failed,
                lines.size()));
        for (String line : lines) {
            say(context, Component.literal(line));
        }
        return failed == 0 ? 1 : 0;
    }

    /** Ends the trip, if there is one. */
    private static int stop(CommandContext<CommandSourceStack> context) {
        Destination target = Navigation.target();
        if (target == null) {
            say(context, Component.translatable("command.howtogo.stop.none"));
            return 0;
        }
        Navigation.clear();
        say(context, Component.translatable("command.howtogo.stop", target.name()));
        return 1;
    }

    /** Switches to the named travel mode, refusing a name that is not one. */
    private static int setMode(CommandContext<CommandSourceStack> context) {
        String asked = StringArgumentType.getString(context, "mode");
        TravelMode mode = byId(asked);
        if (mode == null) {
            context.getSource().sendFailure(
                    Component.translatable("command.howtogo.mode.unknown", asked, modeIds()));
            return 0;
        }
        // setMode announces the switch on the action bar itself, so the command does not say it twice.
        Navigation.setMode(mode);
        return 1;
    }

    /**
     * Moves on to the next travel mode.
     *
     * <p>The same order the hotkey walks them in, so the two ways of doing it cannot disagree about
     * what "next" means.
     */
    private static int cycleMode(CommandContext<CommandSourceStack> context) {
        Navigation.setMode(Navigation.mode().next());
        return 1;
    }

    /** The travel mode an id names, or null when it names none. */
    private static TravelMode byId(String id) {
        if (id == null) {
            return null;
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (TravelMode mode : TravelMode.values()) {
            if (mode.id().equals(wanted)) {
                return mode;
            }
        }
        return null;
    }

    /** Every travel mode's id, for the message that says what the valid ones are. */
    private static String modeIds() {
        StringBuilder ids = new StringBuilder();
        for (TravelMode mode : TravelMode.values()) {
            if (ids.length() > 0) {
                ids.append(", ");
            }
            ids.append(mode.id());
        }
        return ids.toString();
    }

    /** Says something to the player who asked. */
    private static void say(CommandContext<CommandSourceStack> context, Component message) {
        context.getSource().sendSuccess(() -> message, false);
    }
}
