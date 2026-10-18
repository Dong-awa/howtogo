package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.GamePaths;
import bili.dongsz.howtogo.HowToGo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Where this mod's per-world data files live.
 *
 * <h2>Why this is its own class</h2>
 * Two things are stored per world and dimension now -- the road network and the names of the
 * automatically detected rail layer -- and they have to agree on which world and which dimension
 * they are talking about, or a name saved in one dimension would be looked up in another. One
 * resolver, used by both, is the only way that cannot drift apart; two copies of the same four lines
 * would agree today and disagree after the next edit to either.
 *
 * <h2>Why the config directory</h2>
 * Not the save folder: on a multiplayer server the client has no access to the save at all. The
 * dimension is part of the file name because coordinates are only meaningful within one dimension.
 *
 * <h2>Why this is public</h2>
 * Because an addon storing a little of its own data has exactly the same problem, and the same
 * answer: per world, per dimension, beside the roads rather than in a directory it invented. It gets
 * there through {@code HowToGoApi.dataFile(suffix)}, which is the documented way in; this class is
 * public only so that entry point does not have to be a second copy of these four lines.
 */
public final class WorldFiles {

    /** Names the world a directory was made for; see {@link #worldDirectory}. */
    private static final String WORLD_MARKER = ".world";

    /**
     * The longest directory name this writer will produce.
     *
     * <p>Pinyin is longer than the characters it replaces -- one Chinese syllable can take six or
     * seven ASCII letters plus a separator -- so a long Chinese world name would otherwise outgrow
     * the length a single path component is allowed and the directory could not be created at all.
     * The cap is well under that limit and leaves room for the disambiguating suffix. Two names that
     * share the first part of a long pinyin name are still told apart by the world record and the
     * hash, exactly like any other collision.
     */
    private static final int MAX_DIRECTORY_NAME = 120;

    private WorldFiles() {
    }

    /**
     * {@code config/howtogo/<world>/<dimension><suffix>.json} for the level now loaded.
     *
     * @param suffix distinguishes the files stored for one dimension; the road network passes
     *               {@code ""}, which keeps its path exactly what it was before this class existed.
     *               Anything unsafe in a file name is replaced, so a suffix can lengthen the name but
     *               never move the file out of the world's directory
     */
    public static Path of(String suffix) {
        return directory().resolve(fileStem(dimensionName()) + safeSuffix(suffix) + ".json");
    }

    /**
     * The data directory of the level now loaded: {@code config/howtogo/<world>}, with its record.
     *
     * <p>What {@link #of} resolves one file against, and what the import and export need as a whole:
     * exporting a world is packing this directory, and importing into one is replacing it. Asking for
     * it also claims it for this world and writes the record, exactly as asking for a file in it does
     * -- so a world whose map has never been saved still has a directory to export, even if it holds
     * nothing yet.
     */
    static Path directory() {
        Path root = configRoot();
        return root.resolve(worldDirectory(root, worldKey(Minecraft.getInstance())));
    }

    /**
     * {@code config/howtogo}, where every world's data and the transfer folders live.
     *
     * <p>Delegates to {@code GamePaths} rather than reading {@code FMLPaths} here: the answer comes
     * from a different place on each loader, and this class is shared by both. See GamePaths.
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
     * The file-name stem for a dimension: the plain rule, deliberately with no pinyin in it.
     *
     * <h2>Why the dimension does not get the world directory's naming</h2>
     * A dimension id is a resource location ({@code minecraft:overworld}), not a player-facing name:
     * there is nothing in it to transliterate, and what pinyin does to the colon is not harmless --
     * ICU turns it into a colon and a space, so the file becomes {@code minecraft__overworld.json}
     * while every existing world has {@code minecraft_overworld.json}. Silently looking at a
     * different file is exactly the "my roads are gone" failure this mod cannot afford, so the two
     * names are produced by two rules with two jobs: the directory is a name a player reads, and this
     * is a key the mod uses to find its own file again.
     *
     * <p>Package-private so the harness can pin the dimension file names, which are a data
     * compatibility contract rather than an implementation detail: {@link #of} itself needs a game.
     */
    static String fileStem(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return "unknown";
        }
        return dimension.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * The suffix with anything unsafe in a file name replaced.
     *
     * <p>Not {@link #sanitize}, which turns a blank name into "unknown" and would therefore turn the
     * road network's empty suffix into {@code unknownownknown.json}. An empty suffix is not a missing
     * one here -- it is the oldest file this mod has.
     *
     * <p>Package-private rather than private so the harness can assert the boundary directly: what an
     * addon passes through {@code HowToGoApi.dataFile} must lengthen the file name and must not be
     * able to move it out of the world's directory, and {@link #of} itself cannot be called without a
     * running game.
     */
    static String safeSuffix(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return "";
        }
        return suffix.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * The directory one world's data lives in, kept to itself when two worlds clean to one name.
     *
     * <p>A file name may only hold some characters, and {@link #sanitize} replaces the rest -- which
     * means two different world names can clean to the same string. That used to be the case for
     * every Chinese world: the replacement is per character, so {@code 新世界} and {@code 旧世界}
     * both became the same run of underscores, and two saves shared one road network, one opening
     * onto the other's roads and the next autosave writing both sets back into the same file. Pinyin
     * fixes the readability half of that: the directory is now {@code sp_xin_shi_jie}.
     *
     * <p>The directory records which world claimed it, in a file of its own, and a world whose name
     * cleans to a directory another world already owns is given a name with a short hash of its own
     * appended. Nothing is renamed while it can be avoided: a directory without the record -- every
     * directory written before this existed -- is claimed by the world that asks for it first, so the
     * paths players already have keep pointing at the data they already have.
     *
     * <h2>Moving an existing world to its pinyin name</h2>
     * A directory whose record names the world is the world's directory whatever it is called, which
     * is what makes the pinyin name safe to introduce: an existing world in {@code sp_____} is renamed
     * to its pinyin name the first time it is opened after this change, with its files carried along
     * by the rename. If the new name is already taken, or the rename fails, the old directory stays in
     * use -- a world must never lose its roads because its directory name could not be improved.
     * Because the record is the authority rather than the computed name, a later change to the
     * transliteration cannot move a world's data either.
     *
     * <p>Package-private so the harness can exercise the naming and the migration on a temporary
     * directory: {@link #of} itself cannot be called without a running game.
     */
    static String worldDirectory(Path root, String world) {
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

        // A world that predates the pinyin rule lives in the old, all-underscore directory. The
        // first world whose name cleans to it has always claimed it, and renaming it is the moment
        // it gets the record that says so.
        String legacy = legacySanitize(world);
        if (!legacy.equals(desired)) {
            Path legacyDirectory = root.resolve(legacy);
            if (Files.isDirectory(legacyDirectory)
                    && !Files.isRegularFile(legacyDirectory.resolve(WORLD_MARKER))) {
                String renamed = renameIfFree(root, legacy, desired, world);
                if (renamed != null) {
                    return renamed;
                }
                // The pinyin name is taken, so the old directory is claimed and kept in use: an
                // obscure but working name beats a hash-named copy beside a stranded data set.
                writeMarker(legacyDirectory, world);
                return legacy;
            }
        }

        return claim(root, desired, legacy, world);
    }

    /**
     * Uses a free directory name for this world, or a disambiguated form of it.
     *
     * <p>The un-hashed name is tried first, and only when it is absent or already this world's. A
     * directory that exists without a record is only claimed when it is the name the old rule would
     * have produced for this world, which is the first-claim behaviour every pre-record directory
     * relies on; an unrecorded directory under the new pinyin name may be some other world's older
     * data that only the new naming made collide, so it is left alone and this world gets a hash.
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
     * Claims one directory name if it is free or already this world's, or returns null when it is
     * another world's.
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
            // Losing the record costs the disambiguation, not the data: the world still gets a
            // directory and still reads and writes it. Better a shared name than no name at all.
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
        try (java.util.stream.Stream<Path> children = Files.list(root)) {
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
     * Renames a directory to a free name, writing the world record into it afterwards.
     *
     * <p>Returns null when the target is taken or the move fails; the caller then keeps the old
     * directory, so a failed tidy-up costs a name and never data.
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
        try {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                Files.move(source, target);
            }
        } catch (IOException moveFailed) {
            HowToGo.LOGGER.warn("[HowToGo] could not rename world data {} to {}; the old name stays "
                            + "in use: {}", source.getFileName(), target.getFileName(),
                    moveFailed.toString());
            return null;
        }
        writeMarker(target, world);
        return to;
    }

    /** Records this world in a directory, unless it already carries a record. */
    private static void writeMarker(Path directory, String world) {
        Path marker = directory.resolve(WORLD_MARKER);
        if (Files.isRegularFile(marker)) {
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
     * Which world the data belongs to.
     *
     * <p>A singleplayer world is named by its level name and a server by its address, because those
     * are the two things that stay the same across sessions. Two worlds with the same name are the
     * one case this cannot tell apart, and they would share a file.
     */
    private static String worldKey(Minecraft mc) {
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            return "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        ServerData server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return "mp_" + server.ip;
        }
        return "unknown";
    }

    /**
     * The key of the world now loaded, for something that has to say which world it is showing.
     *
     * <p>Public so that the browser map can name the world in its title without a second copy of the
     * rule above -- two copies would agree today and disagree the first time this one is corrected,
     * which is the same argument that put this class in one piece to begin with. This is the name the
     * player gave the world ({@code sp_我的世界}), not the directory it is stored in: the directory
     * name runs through {@link #sanitize}, which transliterates Chinese to pinyin. The page wants the
     * name, the filesystem wants the pinyin.
     */
    public static String currentWorldKey() {
        return worldKey(Minecraft.getInstance());
    }

    /**
     * Strips characters that are not safe in a file name, after transliterating Chinese to pinyin.
     *
     * <p>The transliteration is what keeps a Chinese world name recognisable instead of becoming a
     * row of underscores: {@code sp_新世界} becomes {@code sp_xin_shi_jie}. Anything ICU cannot turn
     * into Latin falls through to the old rule, so a name that transliterates to nothing is unusable
     * as a file name but never unsafe.
     */
    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String latin = Pinyin.latin(raw);
        String safe = (latin == null ? raw : latin).replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) {
            return "unknown";
        }
        return safe.length() > MAX_DIRECTORY_NAME ? safe.substring(0, MAX_DIRECTORY_NAME) : safe;
    }

    /** The old rule, kept only to find the directory a world was stored in before pinyin existed. */
    private static String legacySanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
