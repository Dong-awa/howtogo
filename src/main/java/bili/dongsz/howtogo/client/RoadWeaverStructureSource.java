package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import bili.dongsz.howtogo.place.PlaceKeys;
import bili.dongsz.howtogo.road.PlaceKind;
import bili.dongsz.howtogo.route.Destination;
import bili.dongsz.howtogo.route.DestinationSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Destinations from the structures RoadWeaver connected with its roads.
 *
 * <h2>Why structures RoadWeaver knows about are places worth offering</h2>
 * RoadWeaver's roads exist to join villages, cabins and landmarks, and those are exactly the places a
 * player wants to travel to. Without this source the mod could draw the roads and route along them but
 * could not answer "take me to that village", which is the one thing a road network is for. The
 * positions are RoadWeaver's: a structure is the point it connected a road at, not something this mod
 * measured.
 *
 * <p>Read-only by construction, exactly as {@link CreateStationSource} and {@link MtrStationSource}
 * are: nothing here is saved with the player's own places, and the whole list is replaced when the
 * layer's reading changes. With RoadWeaver absent, switched off, or holding no structures, the source
 * reports itself unavailable and the picker shows only the sources that do have something.
 *
 * <h2>Names</h2>
 * RoadWeaver stores a structure as an id -- {@code minecraft:village_plains}, or a mod's own structure
 * id -- and not as a name, because vanilla has no display name for a structure either. So the name is
 * the structure's translation key when one exists ({@code structure.<namespace>.<path>}), the
 * prettified path for a vanilla structure, and the raw id for anything else. A structure RoadWeaver
 * could not identify is named by its coordinates, which is also the only thing known about it.
 */
public final class RoadWeaverStructureSource implements DestinationSource {

    public static final String ID = "roadweaver";

    /** The layer revision the list below was built from, and the list itself. */
    private int builtRevision = -1;
    private int builtAliasRevision = -1;
    private List<Destination> built = List.of();

    @Override
    public String id() {
        return ID;
    }

    /**
     * Whether there are structures to offer.
     *
     * <p>Asked of the layer rather than of {@link #destinations()}, which builds one destination per
     * structure to answer a question about an empty list: this is called by {@link Destinations#places()}
     * on every frame a map is drawn.
     */
    @Override
    public boolean isAvailable() {
        return RoadConfig.roadWeaverStructures() && !RoadWeaverLayer.structures().isEmpty();
    }

    @Override
    public String displayName() {
        return "hud.howtogo.source.roadweaver";
    }

    /** A place RoadWeaver connected is a place the player can see and travel to, so it is marked. */
    @Override
    public boolean marksPlaces() {
        return true;
    }

    /** After the mod's own five sources, whose order the picker has always had. */
    @Override
    public int priority() {
        return 40;
    }

    /**
     * The structures of the current layer, rebuilt only when the layer or its aliases change.
     *
     * <p>The list is cached against {@link RoadWeaverLayer#revision()} rather than rebuilt per call:
     * this is asked once per frame per map view, and a whole road network's worth of structures is not
     * something to allocate in the middle of a frame. The aliases are a second version because they
     * are the player's own notes in RoadWeaver's map rather than part of the road reading: naming a
     * structure there changes what this list should say without changing a single road.
     */
    @Override
    public List<Destination> destinations() {
        int revision = RoadWeaverLayer.revision();
        int aliasRevision = RoadWeaverLayer.aliasRevision();
        if (revision == builtRevision && aliasRevision == builtAliasRevision) {
            return built;
        }
        List<RoadWeaverLayer.Structure> structures = RoadWeaverLayer.structures();
        List<Destination> destinations = new ArrayList<>(structures.size());
        for (RoadWeaverLayer.Structure structure : structures) {
            // The structure id is the key RoadWeaver itself identifies the place by, so it is what the
            // player's notes are attached to: a new road drawn to the same village must not orphan
            // them. (Two villages of one type share the id; RoadWeaver's reading is what says which
            // key belongs to which of them, and that is the source's choice, not this mod's.)
            destinations.add(new Destination(nameOf(structure), structure.x(), structure.markerY(),
                    structure.z(), ID, Destination.NO_COLOR, PlaceKind.PLACE, structure.id()));
        }
        built = List.copyOf(destinations);
        builtRevision = revision;
        builtAliasRevision = aliasRevision;
        return built;
    }

    /**
     * What to call one of RoadWeaver's structures, in the player's language where there is a name.
     *
     * <p>An alias set in RoadWeaver's own map wins over everything: it is the name the player gave
     * that place, and the only one of these labels somebody chose rather than derived. The id-based
     * name is what is left when there is none. Public because a name is shared by the list, the map
     * label and the route instructions: two independently built strings for one place is how they
     * drift apart.
     */
    public static String nameOf(RoadWeaverLayer.Structure structure) {
        String alias = RoadWeaverLayer.aliasOf(structure);
        if (alias != null) {
            return alias;
        }
        String id = structure.id();
        if (RoadWeaverLayer.isUnknown(id)) {
            return Component.translatable("hud.howtogo.roadweaver.structure",
                    String.valueOf(structure.x()), String.valueOf(structure.z())).getString();
        }
        ResourceLocation location = ResourceLocation.tryParse(id.trim());
        if (location == null) {
            return id;
        }
        String key = "structure." + location.getNamespace() + "." + location.getPath();
        String translated = Component.translatable(key).getString();
        if (!translated.equals(key)) {
            return translated;
        }
        if ("minecraft".equals(location.getNamespace())) {
            return prettify(location.getPath());
        }
        // A mod's structure has no name anywhere this can read: its id is the honest label, and the
        // namespace says which mod it belongs to.
        return id;
    }

    /** A structure id's path as a readable label: underscores and slashes as spaces, first letter up. */
    static String prettify(String path) {
        String spaced = path.replace('_', ' ').replace('/', ' ').trim();
        if (spaced.isEmpty()) {
            return spaced;
        }
        return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }
}

