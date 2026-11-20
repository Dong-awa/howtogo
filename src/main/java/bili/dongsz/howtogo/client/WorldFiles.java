package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.GamePaths;
import bili.dongsz.howtogo.HowToGo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Where this mod's per-world data files live: config/howtogo/<world>/<dimension><suffix>.json.
 * One resolver shared by the road network and the rail layer so both agree on the world and the dimension; the config dir is used because a multiplayer client cannot reach the save. The dimension is in the file name because coordinates only mean something within one. Public so an addon can get a per-world file through HowToGoApi.dataFile without a second copy of this logic.
 *
 * <h2>Which world a directory belongs to</h2>
 * In single player the identity is the save folder, not the display name. Two worlds created with one name get one folder each -- 新的世界 and 新的世界 (1) -- while both keep 新的世界 in level.dat, so keying on the name made the second world open the first one's map. The folder is what a save is; the name is only what it is called.
 * A directory recorded under the old name-based identity is taken over once, when no other save carries that display name, so an existing map is not stranded by the change; see worldDirectory.
 */
public final class WorldFiles {

    /** Names the world a directory was made for; see worldDirectory. */
    private static final String WORLD_MARKER = ".world";

    /**
     * Longest directory name this writer produces. Pinyin is longer than the characters it replaces and a path component has a length limit; the cap leaves room for the disambiguating suffix.
     */
    private static final int MAX_DIRECTORY_NAME = 120;

    /** The level the cached identity belongs to: the key is asked for while some screens draw, so it is not worked out again every frame. */
    private static Object identityLevel;

    /** The identity of identityLevel; null when no load is cached. */
    private static Identity identity;

    private WorldFiles() {
    }

    /**
     * config/howtogo/<world>/<dimension><suffix>.json for the level now loaded. The suffix distinguishes files stored for one dimension (the road network passes "") and is sanitized, so it cannot move the file out of the world's directory.
     */
    public static Path of(String suffix) {
        return directory().resolve(fileStem(dimensionName()) + safeSuffix(suffix) + ".json");
    }

    /**
     * config/howtogo/<world>/<name>.json: a file of this world's own rather than one dimension's. Place details carry the dimension in their key, so one file per world lets the unlinked-details screen count every record. Package-private because addons store per dimension through HowToGoApi.dataFile.
     */
    static Path worldFile(String name) {
        return directory().resolve(safeWorldFileName(name) + ".json");
    }

    /** A file-name stem for worldFile; blank input becomes a usable name rather than ".json". */
    private static String safeWorldFileName(String name) {
        if (name == null || name.isBlank()) {
            return "data";
        }
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "data" : safe;
    }

    /**
     * The data directory of the level now loaded: config/howtogo/<world>, with its record. What of resolves against and what import and export pack; asking for it also claims it for this world, so an unsaved world still has a directory to export.
     */
    static Path directory() {
        Path root = configRoot();
        Identity identity = identityOf(Minecraft.getInstance());
        return root.resolve(worldDirectory(root, identity.key(), identity.previous()));
    }

    /**
     * config/howtogo, where every world's data and the transfer folders live. Delegates to GamePaths for the loader-specific base path.
     */
    static Path configRoot() {
        return GamePaths.modConfigDir();
    }

    /** The dimension of the level now loaded, or "unknown" before one is. */
    private static String dimensionName() {
        Minecraft mc = Minecraft.getInstance();
        Object level = mc.level;
        if (level instanceof ClientLevel clientLevel) {
            return clientLevel.dimension().location().toString();
        }
        return "unknown";
    }

    /**
     * The file-name stem for a dimension, deliberately without pinyin: a dimension id is a resource location, and ICU turns its colon into a colon plus space, so minecraft:overworld would become a different file than the existing minecraft_overworld.json. Package-private so the harness can pin this data-compatibility contract.
     */
    static String fileStem(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return "unknown";
        }
        return dimension.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * The suffix with anything unsafe in a file name replaced. An empty suffix is valid and is the oldest file this mod has, so blank must not become "unknown". Package-private so the harness can assert the boundary directly.
     */
    static String safeSuffix(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return "";
        }
        return suffix.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * What one world's data directory is recorded under, plus the identity it may still be recorded under from before the save folder was part of the key.
     */
    private record Identity(String key, String previous) {
    }

    /**
     * The identity of the world now open. Worked out once per loaded level and kept, because the key is displayed on some screens every frame.
     */
    private static Identity identityOf(Minecraft mc) {
        Object level = mc.level;
        if (level != null && level == identityLevel && identity != null) {
            return identity;
        }
        Identity worked = computeIdentity(mc);
        // Only a loaded level is cached: with none the answer is "unknown", and asking again later must recompute.
        identityLevel = level;
        identity = level == null ? null : worked;
        return worked;
    }

    /**
     * In single player, the save folder, because it is unique per save while the display name is not: creating a second world called 新的世界 gives the folders 新的世界 and 新的世界 (1) but the same name in both level.dat files, so keying on the name made the two worlds read one map.
     * The old name-based identity comes back as the previous one so takeOver can find a map written before this change; it is withheld when another save carries the same name and needs that directory itself.
     * On a server the address is all a client has, exactly as before.
     */
    private static Identity computeIdentity(Minecraft mc) {
        if (mc.hasSingleplayerServer()) {
            MinecraftServer server = mc.getSingleplayerServer();
            if (server != null) {
                String name = server.getWorldData().getLevelName();
                String named = "sp_" + name;
                Path save = savePath(server);
                String folder = save == null ? null : folderOf(save);
                if (folder == null || folder.isBlank()) {
                    return new Identity(named, null);
                }
                String key = "sp_" + folder;
                boolean reusable = !key.equals(named) && !sharesNameWithAnotherSave(save, name, folder);
                return new Identity(key, reusable ? named : null);
            }
        }
        ServerData server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return new Identity("mp_" + server.ip, null);
        }
        return new Identity("unknown", null);
    }

    /** The path of the save now open, or null when it cannot be read. */
    private static Path savePath(MinecraftServer server) {
        try {
            return server.getWorldPath(LevelResource.ROOT);
        } catch (RuntimeException unreadable) {
            HowToGo.LOGGER.warn("[HowToGo] could not read this world's save folder; its data stays "
                    + "under the world name: {}", unreadable.toString());
            return null;
        }
    }

    /** The folder a save path names, or null when it has none. */
    private static String folderOf(Path save) {
        Path name = save.toAbsolutePath().normalize().getFileName();
        return name == null ? null : name.toString();
    }

    /**
     * Whether another save carries this world's display name, which makes the old name-based identity ambiguous.
     * A save's folder is the name it was created under, so the first save with a name sits in saves/<name>; when that folder is not this one, the name-based directory belongs to it and must not be taken over.
     * Package-private so the harness can exercise the guard on a temporary saves directory.
     */
    static boolean sharesNameWithAnotherSave(Path save, String levelName, String folder) {
        if (levelName == null || levelName.isBlank() || levelName.equals(folder)) {
            return false;
        }
        try {
            Path here = save.toAbsolutePath().normalize();
            Path saves = here.getParent();
            if (saves == null) {
                return true;
            }
            Path other = saves.resolve(levelName).toAbsolutePath().normalize();
            return Files.isDirectory(other) && !other.equals(here);
        } catch (RuntimeException unnameable) {
            // A display name that is not a legal path component cannot be checked; leaving the old directory alone is the safe answer.
            return true;
        }
    }

    /**
     * The directory one world's data lives in, kept apart when two worlds clean to one name.
     * Sanitizing maps different names to the same string -- every Chinese world used to collapse to underscores -- so pinyin keeps the name readable and a per-directory marker records which world claimed it; a collision gets a short hash appended.
     * A directory without a record, written before this existed, is claimed by the first world that maps to it, so existing paths keep pointing at existing data.
     * A recorded directory is the world's whatever it is called, so a world in an old underscore directory is renamed to its pinyin name on first open with its files carried along; if the rename fails the old directory stays in use.
     * Package-private so the harness can exercise naming and migration on a temporary directory.
     */
    static String worldDirectory(Path root, String world) {
        return worldDirectory(root, world, null);
    }

    /**
     * The same, for a world whose identity changed: previousWorld is the identity it was recorded under before, or null when there is none to take over. That directory moves to this identity's name before a fresh one is claimed, so the change of key does not lose an existing map.
     */
    static String worldDirectory(Path root, String world, String previousWorld) {
        String desired = sanitize(world);

        // A directory that already records this world is this world's, whatever it is called.
        String owned = directoryOwnedBy(root, world);
        if (owned != null) {
            if (owned.equals(desired)) {
                return owned;
            }
            String renamed = renameIfFree(root, owned, desired, world);
            return renamed != null ? renamed : owned;
        }

        // The identity changed (a save folder replaced the world name): claim what this world wrote before it did.
        if (previousWorld != null && !previousWorld.equals(world)) {
            String takenOver = takeOver(root, previousWorld, desired, world);
            if (takenOver != null) {
                return takenOver;
            }
        }

        // A world that predates the pinyin rule lives in the old underscore directory; the first world whose name maps to it has always claimed it, and the rename is when it gets the record.
        String legacy = legacySanitize(world);
        if (!legacy.equals(desired)) {
            Path legacyDirectory = root.resolve(legacy);
            if (Files.isDirectory(legacyDirectory)
                    && !Files.isRegularFile(legacyDirectory.resolve(WORLD_MARKER))) {
                String renamed = renameIfFree(root, legacy, desired, world);
                if (renamed != null) {
                    return renamed;
                }
                // The pinyin name is taken, so the old directory is claimed and kept in use rather than stranding its data.
                writeMarker(legacyDirectory, world);
                return legacy;
            }
        }

        return claim(root, desired, legacy, world);
    }

    /**
     * Takes over the directory a world had before the save folder became part of its identity, moving it to the name the new identity gives it.
     * A record is authoritative; before records existed the directory was named after the world, under the pinyin rule or the older underscore one, so an unrecorded directory under either old name is this world's.
     * Returns null when there is nothing to take over or the move failed, and the caller claims a free name instead; the old directory is left on disk rather than deleted.
     */
    private static String takeOver(Path root, String previousWorld, String desired, String world) {
        String owner = directoryOwnedBy(root, previousWorld);
        if (owner == null) {
            String[] candidates = {sanitize(previousWorld), legacySanitize(previousWorld)};
            for (String candidate : candidates) {
                Path directory = root.resolve(candidate);
                if (Files.isDirectory(directory)
                        && !Files.isRegularFile(directory.resolve(WORLD_MARKER))) {
                    owner = candidate;
                    break;
                }
            }
        }
        if (owner == null) {
            return null;
        }
        Path source = root.resolve(owner);
        Path target = root.resolve(desired);
        String name = owner;
        if (!owner.equals(desired)) {
            if (Files.exists(target) || !moveDirectory(source, target)) {
                return null;
            }
            name = desired;
        }
        // The record still names the world under its old identity; this directory is the world's under the new one now.
        writeMarker(root.resolve(name), world, true);
        HowToGo.logInfo("[HowToGo] moved the map directory {} to {} for {} after the world "
                + "key began naming the save folder", owner, name, world);
        return name;
    }

    /**
     * Uses a free directory name for this world, or a disambiguated form of it. The plain name is taken when absent or already this world's; an unrecorded directory is claimed only if the old rule produced it for this world, and any other one is left alone while this world gets a hash.
     */
    private static String claim(Path root, String base, String legacy, String world) {
        String claimed = tryClaim(root, base, legacy, world);
        if (claimed != null) {
            return claimed;
        }
        String hashed = base + "-" + Integer.toHexString(world.hashCode());
        for (int attempt = 0; attempt < 16; attempt++) {
            String name = attempt == 0 ? hashed : hashed + "-" + attempt;
            claimed = tryClaim(root, name, name, world);
            if (claimed != null) {
                return claimed;
            }
        }
        HowToGo.LOGGER.warn("[HowToGo] no free data directory for world '{}'; using {}",
                world, base);
        return base;
    }

    /**
     * Claims one directory name if it is free or already this world's, or returns null when it is another world's.
     */
    private static String tryClaim(Path root, String base, String legacy, String world) {
        Path directory = root.resolve(base);
        Path marker = directory.resolve(WORLD_MARKER);
        try {
            if (Files.isRegularFile(marker)) {
                String claimedBy = Files.readString(marker, StandardCharsets.UTF_8).trim();
                return claimedBy.equals(world) ? base : null;
            }
            if (Files.exists(directory) && !base.equals(legacy)) {
                return null;
            }
            Files.createDirectories(directory);
            Files.writeString(marker, world, StandardCharsets.UTF_8);
            return base;
        } catch (IOException e) {
            // Losing the record costs disambiguation, not data: the world still gets a usable directory.
            HowToGo.LOGGER.warn("[HowToGo] could not record which world owns {}: {}",
                    marker.getParent(), e.toString());
            return base;
        }
    }

    /** The name of the directory that records this world, or null when none does. */
    private static String directoryOwnedBy(Path root, String world) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                Path marker = child.resolve(WORLD_MARKER);
                if (!Files.isRegularFile(marker)) {
                    continue;
                }
                try {
                    if (Files.readString(marker, StandardCharsets.UTF_8).trim().equals(world)) {
                        return child.getFileName().toString();
                    }
                } catch (IOException unreadable) {
                    // Try the next directory: one unreadable record must not hide the rest.
                }
            }
        } catch (IOException unreadableRoot) {
            // Fall back to the computed name and the claim logic below.
        }
        return null;
    }

    /**
     * Renames a directory to a free name and writes the world record into it afterwards. Returns null when the target is taken or the move fails, so a failed tidy-up costs a name and never data.
     */
    private static String renameIfFree(Path root, String from, String to, String world) {
        if (from.equals(to)) {
            return to;
        }
        Path source = root.resolve(from);
        Path target = root.resolve(to);
        if (!Files.isDirectory(source) || Files.exists(target)) {
            return null;
        }
        if (!moveDirectory(source, target)) {
            return null;
        }
        writeMarker(target, world);
        return to;
    }

    /** Moves a directory, atomically where the filesystem allows; false when nothing was moved. */
    private static boolean moveDirectory(Path source, Path target) {
        try {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                Files.move(source, target);
            }
            return true;
        } catch (IOException moveFailed) {
            HowToGo.LOGGER.warn("[HowToGo] could not move {} to {}; the old directory stays in use: {}",
                    source, target, moveFailed.toString());
            return false;
        }
    }

    /** Records this world in a directory, unless it already carries a record. */
    private static void writeMarker(Path directory, String world) {
        writeMarker(directory, world, false);
    }

    /**
     * Records this world in a directory. replace is only for a directory taken over from the old identity, whose record names a world that no longer uses it.
     */
    private static void writeMarker(Path directory, String world, boolean replace) {
        Path marker = directory.resolve(WORLD_MARKER);
        if (!replace && Files.isRegularFile(marker)) {
            return;
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(marker, world, StandardCharsets.UTF_8);
        } catch (IOException e) {
            HowToGo.LOGGER.warn("[HowToGo] could not record which world owns {}: {}",
                    directory, e.toString());
        }
    }

    /**
     * The key of the world now loaded, for anything that must name the world it is showing. Public to avoid a second copy of the rule; this is the save the player chose, not the sanitized pinyin directory.
     */
    public static String currentWorldKey() {
        return identityOf(Minecraft.getInstance()).key();
    }

    /**
     * Strips characters that are not safe in a file name after transliterating Chinese to pinyin, so sp_新世界 becomes sp_xin_shi_jie. Anything ICU cannot turn into Latin falls through to the old rule.
     * Runs of unsafe characters become one underscore and a trailing one is dropped, so a duplicate save's " (1)" suffix reads as one more word instead of a row of underscores.
     */
    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String latin = Pinyin.latin(raw);
        String safe = tidy((latin == null ? raw : latin).replaceAll("[^A-Za-z0-9._-]", "_"));
        if (safe.isBlank()) {
            return "unknown";
        }
        return safe.length() > MAX_DIRECTORY_NAME ? safe.substring(0, MAX_DIRECTORY_NAME) : safe;
    }

    /**
     * One underscore per run of replaced characters, and no trailing one; the leading underscore of a world key is kept because it separates sp or mp from the name.
     */
    private static String tidy(String safe) {
        String joined = safe.replaceAll("_{2,}", "_");
        return joined.length() > 3 && joined.endsWith("_")
                ? joined.substring(0, joined.length() - 1) : joined;
    }

    /** The old rule, kept only to find the directory a world was stored in before pinyin existed. */
    private static String legacySanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
