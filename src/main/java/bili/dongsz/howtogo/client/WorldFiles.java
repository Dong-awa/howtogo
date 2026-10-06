package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
 */
final class WorldFiles {

    /** Names the world a directory was made for; see {@link #worldDirectory}. */
    private static final String WORLD_MARKER = ".world";

    private WorldFiles() {
    }

    /**
     * {@code config/howtogo/<world>/<dimension><suffix>.json} for the level now loaded.
     *
     * @param suffix distinguishes the files stored for one dimension; the road network passes
     *               {@code ""}, which keeps its path exactly what it was before this class existed
     */
    static Path of(String suffix) {
        Minecraft mc = Minecraft.getInstance();
        Object level = mc.level;

        String dimension = "unknown";
        if (level instanceof ClientLevel clientLevel) {
            dimension = clientLevel.dimension().location().toString();
        }
        Path root = FMLPaths.CONFIGDIR.get().resolve(HowToGo.MODID);
        return root.resolve(worldDirectory(root, worldKey(mc)))
                .resolve(sanitize(dimension) + suffix + ".json");
    }

    /**
     * The directory one world's data lives in, kept to itself when two worlds clean to one name.
     *
     * <p>A file name may only hold some characters, and {@link #sanitize} replaces the rest -- which
     * means two different world names can clean to the same string. Chinese names are the case that
     * matters here, because the replacement is per character: {@code 新世界} and {@code 旧世界} both
     * become three underscores, so two saves shared one road network, one opening onto the other's
     * roads and the next autosave writing both sets back into the same file.
     *
     * <p>So the directory records which world claimed it, in a file of its own, and a world whose name
     * cleans to a directory another world already owns is given a name with a short hash of its own
     * appended. Nothing is renamed and nothing is migrated: a directory without the record -- every
     * directory written before this existed -- is simply claimed by the world that asks for it first,
     * so the paths players already have keep pointing at the data they already have.
     */
    private static String worldDirectory(Path root, String world) {
        String base = sanitize(world);
        Path marker = root.resolve(base).resolve(WORLD_MARKER);
        try {
            if (Files.isRegularFile(marker)) {
                String claimedBy = Files.readString(marker, StandardCharsets.UTF_8).trim();
                if (!claimedBy.equals(world)) {
                    return base + "-" + Integer.toHexString(world.hashCode());
                }
                return base;
            }
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, world, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Losing the record costs the disambiguation, not the data: the world still gets a
            // directory and still reads and writes it. Better a shared name than no name at all.
            HowToGo.LOGGER.warn("[HowToGo] could not record which world owns {}: {}",
                    marker.getParent(), e.toString());
        }
        return base;
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

    /** Strips characters that are not safe in a file name. */
    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
