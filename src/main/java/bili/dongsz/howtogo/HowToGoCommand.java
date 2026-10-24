// The two loaders build the same nine commands with the same tree; only the source type and the
// registration differ. NeoForge hands a RegisterClientCommandsEvent carrying the dispatcher and types
// the source as CommandSourceStack; Fabric hands a fresh client dispatcher to a callback and types it
// as FabricClientCommandSource, with ClientCommandManager building the nodes.
//
// The class is written twice rather than conditioned inside, because the source type appears in every
// signature in the file -- nine of them -- and a condition at each would be worse to read than either
// half alone. What is genuinely shared -- the command names, the bodies, the messages -- is identical
// in both halves.
//? if fabric {
/*
package bili.dongsz.howtogo;

import bili.dongsz.howtogo.api.SelfChecks;
import bili.dongsz.howtogo.client.DestinationScreen;
import bili.dongsz.howtogo.client.MapDataScreen;
import bili.dongsz.howtogo.client.MapDataTransfer;
import bili.dongsz.howtogo.client.MapImportScreen;
import bili.dongsz.howtogo.client.Navigation;
import bili.dongsz.howtogo.client.RoadStore;
import bili.dongsz.howtogo.client.Screens;
import bili.dongsz.howtogo.client.SelfTest;
import bili.dongsz.howtogo.client.WorldFiles;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.webmap.WebMapService;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// 
// * The mod's own commands: open the terminal, end the trip, switch how you are travelling.
// *
// * <h2>Why these are client commands</h2>
// * Everything they do -- opening a screen, dropping a route, changing a travel mode -- is state this
// * client keeps for itself, and none of it means anything to the server. Registered as client commands
// * they therefore run here, without a round trip, without the server needing this mod, and without
// * needing permission for anything: a player on somebody else's server gets the same three commands a
// * player in their own world does, which is the only version of this that makes sense. On a client the
// * server's dispatcher is not even consulted for a command it does not own.
// *
// * <h2>Why opening the terminal is deferred a tick</h2>
// * A command runs inside the chat screen's own handling of the line that was typed, and by the time it
// * returns that screen is still the one the client thinks is open. Opening another screen from in there
// * is what makes a key or a command look dead: the new screen is set, the old one's close puts the game
// * screen back, and the player is left looking at the world. The work is therefore parked and run on the
// * next client tick, which is the same arrangement the editor uses for the screens it opens from a key.
// 
public final class HowToGoCommand {

// The command, and the short form of it. 
    private static final String ROOT = "howtogo";
    private static final String ALIAS = "htg";

// Work deferred out of command handling to the next client tick. 
    private static Runnable pending;

    private HowToGoCommand() {
    }

// 
// * Registers the commands.
// *
// * <p>On the client's own dispatcher, so the tree is rebuilt whenever the client is -- a reconnect,
// * or a change of server -- and never goes stale.
// 
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // Registered twice rather than redirected: a redirect would put one node in two trees, and
            // the client's dispatcher is rebuilt from scratch each time, so building the tree again
            // costs nothing and keeps the two spellings genuinely independent.
            dispatcher.register(tree(ROOT));
            dispatcher.register(tree(ALIAS));
        });
    }

// Runs whatever a command had to leave for the next tick. 
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

    private static LiteralArgumentBuilder<FabricClientCommandSource> tree(String name) {
        return ClientCommandManager.literal(name)
                .executes(HowToGoCommand::help)
                .then(ClientCommandManager.literal("terminal")
                        .executes(HowToGoCommand::terminal))
                .then(ClientCommandManager.literal("stop")
                        .executes(HowToGoCommand::stop))
                .then(ClientCommandManager.literal("selftest")
                        .executes(HowToGoCommand::selfTest))
                .then(ClientCommandManager.literal("webmap")
                        .executes(HowToGoCommand::webMapStart)
                        .then(ClientCommandManager.literal("start")
                                .executes(HowToGoCommand::webMapStart))
                        .then(ClientCommandManager.literal("stop")
                                .executes(HowToGoCommand::webMapStop))
                        .then(ClientCommandManager.literal("status")
                                .executes(HowToGoCommand::webMapStatus)))
                .then(ClientCommandManager.literal("mode")
                        .executes(HowToGoCommand::cycleMode)
                        .then(ClientCommandManager.argument("mode", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    for (TravelMode mode : TravelMode.values()) {
                                        builder.suggest(mode.id());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(HowToGoCommand::setMode)));
    }

// Says what the command does, rather than doing something surprising with no arguments. 
    private static int help(CommandContext<FabricClientCommandSource> context) {
        // One argument per placeholder, all of them the same string: the lang line spells each
        // subcommand out in full, and a shortfall here does not strip the extra placeholders, it
        // makes the whole line fail to format and the player read the raw pattern instead.
        String root = "/" + ROOT;
        say(context, Component.translatable("command.howtogo.help", root, root, root, root, root));
        return 1;
    }

// 
// * Opens the destination picker, the same screen the main screen (K) opens from its button.
// *
// * <p>The parent screen is asked for on the tick the screen is built and not here, so that whatever
// * the player is looking at when the command runs is the screen it comes back to.
// 
    private static int terminal(CommandContext<FabricClientCommandSource> context) {
        pending = () -> {
            Minecraft minecraft = Minecraft.getInstance();
            Screens.set(new DestinationScreen(Screens.of(minecraft)));
        };
        say(context, Component.translatable("command.howtogo.terminal"));
        return 1;
    }

// 
// * Runs the mod's own checks against the world that is open, and reports what they found.
// *
// * <p>The one test that has to run in the game: whether the roads the player drew are the roads the
// * mod loaded, whether their lines can carry a journey across the world as it stands, and what the
// * guidance would actually say on the way. It sets a destination and puts back the one being
// * navigated, which is why this says so before it runs -- see {@link SelfTest}.
// *
// * <p>The report goes to the log in full and to chat one line at a time, so a finding can be read
// * without alt-tabbing and quoted from the log afterwards.
// *
// * <p>Checks contributed by other mods run after this mod's own and land in the same report, in the
// * same shape: whether what is in this world is what the mod thinks is in it is the same question
// * for a source another mod contributed, and the player asking it is already here.
// 
    private static int selfTest(CommandContext<FabricClientCommandSource> context) {
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

// 
// * Starts the browser map's server, or says where it already is.
// *
// * <p>The URL is a clickable link: the point of this feature is a page in the player's own browser,
// * and asking them to copy a port number out of a chat line by hand is the kind of friction that
// * makes a feature not get used. The link opens the default browser through the game's own
// * platform helper, which is the same path the chat's other links take.
// 
    private static int webMapStart(CommandContext<FabricClientCommandSource> context) {
        String running = WebMapService.url();
        if (running != null) {
            say(context, Component.translatable("command.howtogo.webmap.running", urlLink(running)));
            return 1;
        }
        try {
            say(context, Component.translatable("command.howtogo.webmap.started",
                    urlLink(WebMapService.start())));
            return 1;
        } catch (IOException | RuntimeException failed) {
            // Reported rather than logged and forgotten: the usual cause is a port the player could
            // change, and a failure they cannot see is one they cannot fix.
            HowToGo.LOGGER.warn("[HowToGo] webmap | could not start: {}", failed.toString());
            context.getSource().sendError(Component.translatable("command.howtogo.webmap.failed",
                    String.valueOf(failed.getMessage())));
            return 0;
        }
    }

// Stops the server, and says whether there was one. 
    private static int webMapStop(CommandContext<FabricClientCommandSource> context) {
        if (WebMapService.stop()) {
            say(context, Component.translatable("command.howtogo.webmap.stopped"));
            return 1;
        }
        say(context, Component.translatable("command.howtogo.webmap.not_running"));
        return 0;
    }

// 
// * Says whether the map is up, at which address, and what the page would show right now.
// *
// * <p>Run on the client thread, which is why it may read {@link RoadStore} directly: a command is
// * executed on the client's own thread, so this needs none of the hand-off the HTTP side does.
// 
    private static int webMapStatus(CommandContext<FabricClientCommandSource> context) {
        String url = WebMapService.url();
        if (url == null) {
            say(context, Component.translatable("command.howtogo.webmap.not_running"));
            return 0;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            say(context, Component.translatable("command.howtogo.webmap.status_no_world",
                    urlLink(url), Component.translatable("command.howtogo.webmap.no_world")));
            return 1;
        }
        RoadNetwork network = RoadStore.get();
        Component where = Component.translatable("command.howtogo.webmap.where",
                WorldFiles.currentWorldKey(),
                minecraft.level.dimension().location().toString());
        Component stats = Component.translatable("command.howtogo.webmap.stats",
                network.nodeCount(), network.segmentCount(),
                String.format(Locale.ROOT, "%.2f", networkLengthKilometres(network)));
        say(context, Component.translatable("command.howtogo.webmap.status", urlLink(url), where,
                stats));
        return 1;
    }

// The total length of every road in the network, in kilometres, for the status line. 
    private static double networkLengthKilometres(RoadNetwork network) {
        double blocks = 0.0;
        for (RoadSegment segment : network.segments()) {
            blocks += segment.length();
        }
        return blocks / 1000.0;
    }

// A URL a player can click, styled as the link it is. 
    private static Component urlLink(String url) {
        return Component.literal(url).withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                // 1.21.6 replaced the ClickEvent(Action, String) constructor with one subclass per
                // action. OpenUrl is the same event carrying the same link but holds a URI, and the
                // build's own replacement rule rewrites this line for the nodes that need it -- see
                // the header of stonecutter.gradle.kts. It is a rule rather than a condition here
                // because the whole command tree is written once per loader, and a version condition
                // inside a loader condition would nest.
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
    }

// Ends the trip, if there is one. 
    private static int stop(CommandContext<FabricClientCommandSource> context) {
        Destination target = Navigation.target();
        if (target == null) {
            say(context, Component.translatable("command.howtogo.stop.none"));
            return 0;
        }
        Navigation.clear();
        say(context, Component.translatable("command.howtogo.stop", target.name()));
        return 1;
    }

// Switches to the named travel mode, refusing a name that is not one. 
    private static int setMode(CommandContext<FabricClientCommandSource> context) {
        String asked = StringArgumentType.getString(context, "mode");
        TravelMode mode = byId(asked);
        if (mode == null) {
            context.getSource().sendError(
                    Component.translatable("command.howtogo.mode.unknown", asked, modeIds()));
            return 0;
        }
        // setMode announces the switch on the action bar itself, so the command does not say it twice.
        Navigation.setMode(mode);
        return 1;
    }

// 
// * Moves on to the next travel mode.
// *
// * <p>The same order the hotkey walks them in, so the two ways of doing it cannot disagree about
// * what "next" means.
// 
    private static int cycleMode(CommandContext<FabricClientCommandSource> context) {
        Navigation.setMode(Navigation.mode().next());
        return 1;
    }

// The travel mode an id names, or null when it names none. 
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

// Every travel mode's id, for the message that says what the valid ones are. 
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

// Says something to the player who asked. 
    private static void say(CommandContext<FabricClientCommandSource> context, Component message) {
                // The client source has no sendSuccess: Fabric spells the same thing sendFeedback,
        // and takes the message itself rather than a supplier.
        context.getSource().sendFeedback(message);
    }
}

*///?}
//? if neoforge {
package bili.dongsz.howtogo;

import bili.dongsz.howtogo.api.SelfChecks;
import bili.dongsz.howtogo.client.DestinationScreen;
import bili.dongsz.howtogo.client.MapDataScreen;
import bili.dongsz.howtogo.client.MapDataTransfer;
import bili.dongsz.howtogo.client.MapImportScreen;
import bili.dongsz.howtogo.client.Navigation;
import bili.dongsz.howtogo.client.RoadStore;
import bili.dongsz.howtogo.client.Screens;
import bili.dongsz.howtogo.client.SelfTest;
import bili.dongsz.howtogo.client.WorldFiles;
import bili.dongsz.howtogo.road.RoadNetwork;
import bili.dongsz.howtogo.road.RoadSegment;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.TravelMode;
import bili.dongsz.howtogo.webmap.WebMapService;
import bili.dongsz.howtogo.worlddata.WorldArchive;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.io.IOException;
import java.nio.file.Path;
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
                .then(Commands.literal("data")
                        .executes(HowToGoCommand::mapData))
                .then(Commands.literal("export")
                        .executes(HowToGoCommand::mapExport))
                .then(Commands.literal("import")
                        .executes(HowToGoCommand::mapImportOpen)
                        .then(Commands.argument("archive", StringArgumentType.greedyString())
                                .executes(HowToGoCommand::mapImportFile)))
                .then(Commands.literal("webmap")
                        .executes(HowToGoCommand::webMapStart)
                        .then(Commands.literal("start")
                                .executes(HowToGoCommand::webMapStart))
                        .then(Commands.literal("stop")
                                .executes(HowToGoCommand::webMapStop))
                        .then(Commands.literal("status")
                                .executes(HowToGoCommand::webMapStatus)))
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
        // One argument per placeholder, all of them the same string: the lang line spells each
        // subcommand out in full, and a shortfall here does not strip the extra placeholders, it
        // makes the whole line fail to format and the player read the raw pattern instead.
        String root = "/" + ROOT;
        say(context, Component.translatable("command.howtogo.help",
                root, root, root, root, root, root, root, root));
        return 1;
    }

    /**
     * Opens the destination picker, the same screen the main screen (K) opens from its button.
     *
     * <p>The parent screen is asked for on the tick the screen is built and not here, so that whatever
     * the player is looking at when the command runs is the screen it comes back to.
     */
    private static int terminal(CommandContext<CommandSourceStack> context) {
        pending = () -> {
            Minecraft minecraft = Minecraft.getInstance();
            Screens.set(new DestinationScreen(Screens.of(minecraft)));
        };
        say(context, Component.translatable("command.howtogo.terminal"));
        return 1;
    }

    /**
     * Opens the map transfer screen: export this world's map, or import one over it.
     *
     * <p>Deferred a tick like {@link #terminal}, and for the same reason -- a screen opened from
     * inside the chat screen's handling of the line that asked for it is closed again by the chat
     * screen's own close.
     */
    private static int mapData(CommandContext<CommandSourceStack> context) {
        pending = () -> {
            Minecraft minecraft = Minecraft.getInstance();
            Screens.set(new MapDataScreen(Screens.of(minecraft)));
        };
        say(context, Component.translatable("command.howtogo.mapdata"));
        return 1;
    }

    /** `/howtogo import` without a file: the same screen, which is the one that lists archives. */
    private static int mapImportOpen(CommandContext<CommandSourceStack> context) {
        pending = () -> {
            Minecraft minecraft = Minecraft.getInstance();
            Screens.set(new MapDataScreen(Screens.of(minecraft)));
        };
        say(context, Component.translatable("command.howtogo.import.open"));
        return 1;
    }

    /**
     * Exports the current world's map immediately, and reports where the archive went.
     *
     * <p>No confirmation, because nothing is being replaced: the worst an accidental export costs is
     * a file in the exports folder. The folder is a clickable {@code open_file} link so the player can
     * actually find the archive they are about to send to somebody.
     */
    private static int mapExport(CommandContext<CommandSourceStack> context) {
        try {
            WorldArchive.Packed packed = MapDataTransfer.exportNow();
            Component folder = Component.translatable("command.howtogo.export.open_folder")
                    .withStyle(Style.EMPTY
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            // Same event, same folder: the rule that rewrites the OPEN_URL site above
                            // also rewrites this one for the nodes that name a subclass per action.
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE,
                                    MapDataTransfer.exportsDirectory().toAbsolutePath().toString())));
            say(context, Component.translatable("command.howtogo.export.done",
                    packed.file.getFileName().toString(), packed.files,
                    MapDataTransfer.humanSize(packed.bytes), folder));
            return 1;
        } catch (IOException | RuntimeException failed) {
            HowToGo.LOGGER.warn("[HowToGo] map export failed: {}", failed.toString());
            context.getSource().sendFailure(
                    Component.translatable("command.howtogo.export.failed", MapDataTransfer.describe(failed)));
            return 0;
        }
    }

    /**
     * Reads an archive named on the command line and opens the overwrite warning for it.
     *
     * <p>Reading it here rather than in the screen is what makes a wrong path fail in chat, where the
     * player typed it: the alternative is a screen that opens and then cannot say anything useful. The
     * import itself still happens only after the warning's button is pressed -- a command line is not
     * a consent to overwrite a world.
     */
    private static int mapImportFile(CommandContext<CommandSourceStack> context) {
        String typed = StringArgumentType.getString(context, "archive");
        Path archive = Path.of(typed);
        if (!archive.isAbsolute()) {
            archive = Minecraft.getInstance().gameDirectory.toPath().resolve(archive);
        }
        archive = archive.normalize();
        try {
            WorldArchive.Contents contents = MapDataTransfer.inspect(archive);
            Path finalArchive = archive;
            pending = () -> {
                Minecraft minecraft = Minecraft.getInstance();
                Screens.set(new MapImportScreen(Screens.of(minecraft), Screens.of(minecraft),
                        finalArchive, contents));
            };
            say(context, Component.translatable("command.howtogo.import.confirm"));
            return 1;
        } catch (IOException | RuntimeException failed) {
            HowToGo.LOGGER.warn("[HowToGo] could not read map archive {}: {}", archive,
                    failed.toString());
            context.getSource().sendFailure(Component.translatable("command.howtogo.import.failed",
                    MapDataTransfer.describe(failed)));
            return 0;
        }
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

    /**
     * Starts the browser map's server, or says where it already is.
     *
     * <h2>Why the browser is opened here rather than by the player clicking</h2>
     * The chat's own links go through vanilla's {@code ConfirmLinkScreen} -- "open this URL?" -- which
     * is a dialog in the middle of a command that already said what it is going to do. A manual start
     * opens the page itself through the game's platform helper, so the feature is one command and no
     * confirmation; the URL is still printed, plainly, for a player who wants it for a bookmark or for
     * another machine.
     */
    private static int webMapStart(CommandContext<CommandSourceStack> context) {
        String running = WebMapService.url();
        if (running != null) {
            say(context, Component.translatable("command.howtogo.webmap.running", urlLink(running)));
            return 1;
        }
        try {
            String url = WebMapService.start();
            openInBrowser(url);
            say(context, Component.translatable("command.howtogo.webmap.started", urlLink(url)));
            return 1;
        } catch (IOException | RuntimeException failed) {
            // Reported rather than logged and forgotten: the usual cause is a port the player could
            // change, and a failure they cannot see is one they cannot fix.
            HowToGo.LOGGER.warn("[HowToGo] webmap | could not start: {}", failed.toString());
            context.getSource().sendFailure(Component.translatable("command.howtogo.webmap.failed",
                    String.valueOf(failed.getMessage())));
            return 0;
        }
    }

    /**
     * Opens the browser map in the player's default browser, without the chat-link confirmation.
     *
     * <p>The same thing clicking a chat link does after its dialog. A failure here is not a failed
     * start: the server is up and the URL is on screen, so it is logged and nothing else changes.
     */
    public static void openInBrowser(String url) {
        try {
            bili.dongsz.howtogo.client.OsShell.openUrl(url);
        } catch (RuntimeException | LinkageError failed) {
            HowToGo.LOGGER.info("[HowToGo] webmap | could not open a browser at {} ({}); "
                    + "the address is on screen", url, failed.toString());
        }
    }

    /** Stops the server, and says whether there was one. */
    private static int webMapStop(CommandContext<CommandSourceStack> context) {
        if (WebMapService.stop()) {
            say(context, Component.translatable("command.howtogo.webmap.stopped"));
            return 1;
        }
        say(context, Component.translatable("command.howtogo.webmap.not_running"));
        return 0;
    }

    /**
     * Says whether the map is up, at which address, and what the page would show right now.
     *
     * <p>Run on the client thread, which is why it may read {@link RoadStore} directly: a command is
     * executed on the client's own thread, so this needs none of the hand-off the HTTP side does.
     */
    private static int webMapStatus(CommandContext<CommandSourceStack> context) {
        String url = WebMapService.url();
        if (url == null) {
            say(context, Component.translatable("command.howtogo.webmap.not_running"));
            return 0;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            say(context, Component.translatable("command.howtogo.webmap.status_no_world",
                    urlLink(url), Component.translatable("command.howtogo.webmap.no_world")));
            return 1;
        }
        RoadNetwork network = RoadStore.get();
        Component where = Component.translatable("command.howtogo.webmap.where",
                WorldFiles.currentWorldKey(),
                minecraft.level.dimension().location().toString());
        Component stats = Component.translatable("command.howtogo.webmap.stats",
                network.nodeCount(), network.segmentCount(),
                String.format(Locale.ROOT, "%.2f", networkLengthKilometres(network)));
        say(context, Component.translatable("command.howtogo.webmap.status", urlLink(url), where,
                stats));
        return 1;
    }

    /** The total length of every road in the network, in kilometres, for the status line. */
    private static double networkLengthKilometres(RoadNetwork network) {
        double blocks = 0.0;
        for (RoadSegment segment : network.segments()) {
            blocks += segment.length();
        }
        return blocks / 1000.0;
    }

    /** A URL a player can click, styled as the link it is. */
    private static Component urlLink(String url) {
        return Component.literal(url).withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                // 1.21.6 replaced the ClickEvent(Action, String) constructor with one subclass per
                // action. OpenUrl is the same event carrying the same link but holds a URI, and the
                // build's own replacement rule rewrites this line for the nodes that need it -- see
                // the header of stonecutter.gradle.kts. It is a rule rather than a condition here
                // because the whole command tree is written once per loader, and a version condition
                // inside a loader condition would nest.
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
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
//?}
