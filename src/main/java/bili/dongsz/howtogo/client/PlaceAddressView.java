package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.place.PlaceAddress;
import bili.dongsz.howtogo.road.RoadNetwork;
import net.minecraft.network.chat.Component;

/**
 * The address line of a place card and of the edit dialog, with the cost of asking hidden behind one
 * cache.
 *
 * <h2>Why the caching is here and not in {@link PlaceAddress}</h2>
 * The geometry is a property of the network; how often it is asked is a property of the interface.
 * A card is drawn every frame, and the destination picker's card is drawn while a map redraws behind
 * it, so an uncached call would rebuild a spatial index and a chain grouping of the whole network
 * sixty times a second for an answer that only changes when a road moves or the player opens a
 * different place. Neither of those events is a frame. The pure class stays pure and cheap to test;
 * this one keeps the last answer and the three numbers it was derived from.
 *
 * <h2>Why one entry is enough</h2>
 * Exactly one details card is on screen at a time -- the picker's band, the main screen's floating
 * card, and the dialog that edits the same place, never two of them. A one-entry cache is therefore
 * a full cache for this caller, and an LRU keyed on place id would be a hash map to answer a
 * question that has one asker. The key is (network identity, revision, x, z): the revision is what
 * catches a road that moved under the same network object, which is how the editor mutates it.
 *
 * <h2>Why the wording lives here</h2>
 * {@link PlaceAddress} returns what the address <em>is</em>; the words for "east side", "12 blocks"
 * and "no road nearby" are translations, and translations are a client concern. Keeping them beside
 * the cache means the card and the dialog print the same sentence: two formattings of the same
 * record is how "east side" and "east of it" end up on two screens for one road.
 */
public final class PlaceAddressView {

    /**
     * How far a road may be and still be this place's address, in blocks.
     *
     * <p>About the length of a city block, which is the scale at which "near the road" is a useful
     * thing to say. Larger would start naming a road across a field the player would not walk to;
     * smaller would leave a plaza in the middle of a block with no address at all. The constant lives
     * here rather than in the pure class because it is a presentation choice, and the checks pass
     * their own small numbers instead.
     */
    public static final double RADIUS = 64.0;

    /** The whole line: caption and value, e.g. {@code 地址：人民路 东侧 12 格}. */
    public static final String LINE_KEY = "screen.howtogo.place_details.address_line";
    /** The value when no road is within {@link #RADIUS}: {@code 附近没有道路}. */
    public static final String NO_ROAD_KEY = "screen.howtogo.place_details.address_no_road";
    /** What an unnamed road is called in the line, so it is not left blank. */
    public static final String UNNAMED_ROAD_KEY = "screen.howtogo.place_details.address_unnamed";
    /** Road, side and distance: {@code %s %s %s} / {@code %s, %s, %s}. */
    public static final String WITH_SIDE_KEY = "screen.howtogo.place_details.address_with_side";
    /** Road and distance, when there is no side to name (on the road, or a diagonal tie). */
    public static final String NO_SIDE_KEY = "screen.howtogo.place_details.address_no_side";
    /** Road only, for a place standing on it: {@code %s 旁}. */
    public static final String ON_ROAD_KEY = "screen.howtogo.place_details.address_on_road";
    /** The distance unit: {@code %s 格} / {@code %s blocks}. */
    public static final String DISTANCE_KEY = "screen.howtogo.place_details.address_distance";
    public static final String SIDE_NORTH_KEY = "screen.howtogo.place_details.side.north";
    public static final String SIDE_EAST_KEY = "screen.howtogo.place_details.side.east";
    public static final String SIDE_SOUTH_KEY = "screen.howtogo.place_details.side.south";
    public static final String SIDE_WEST_KEY = "screen.howtogo.place_details.side.west";

    /** The network and position the cached answer was computed from; see the class comment. */
    private static RoadNetwork cachedNetwork;
    private static int cachedRevision = Integer.MIN_VALUE;
    private static int cachedX = Integer.MIN_VALUE;
    private static int cachedZ = Integer.MIN_VALUE;
    private static PlaceAddress.Address cached = PlaceAddress.Address.NONE;

    private PlaceAddressView() {
    }

    /** The address of a block, answered from the cache when the roads and the block are unchanged. */
    public static PlaceAddress.Address addressAt(int x, int z) {
        RoadNetwork network = RoadStore.get();
        int revision = network.revision();
        if (network == cachedNetwork && revision == cachedRevision && x == cachedX && z == cachedZ) {
            return cached;
        }
        cached = PlaceAddress.nearest(network, x, z, RADIUS);
        cachedNetwork = network;
        cachedRevision = revision;
        cachedX = x;
        cachedZ = z;
        return cached;
    }

    /** The whole read-only line for a block, ready to draw with {@code drawString}. */
    public static String line(int x, int z) {
        return line(addressAt(x, z));
    }

    /**
     * The whole read-only line for an address already computed.
     *
     * <p>Separate from {@link #line(int, int)} so a caller that has just asked for the record -- and
     * may want the class or the side for something else -- does not pay for the cache lookup twice.
     * A null address reads as {@link PlaceAddress.Address#NONE}, because a card that is drawn before
     * the first address has been worked out should say what a roadless place says, not throw.
     */
    public static String line(PlaceAddress.Address address) {
        PlaceAddress.Address value = address == null ? PlaceAddress.Address.NONE : address;
        if (!value.nearRoad()) {
            return translate(NO_ROAD_KEY);
        }
        String road = value.onRoad() ? value.roadName() : translate(UNNAMED_ROAD_KEY);
        String distance = Component.translatable(DISTANCE_KEY,
                String.valueOf(value.distanceBlocks())).getString();
        String described;
        if (value.side() == PlaceAddress.Side.NONE) {
            described = value.distanceBlocks() == 0
                    ? Component.translatable(ON_ROAD_KEY, road).getString()
                    : Component.translatable(NO_SIDE_KEY, road, distance).getString();
        } else {
            described = Component.translatable(WITH_SIDE_KEY, road, sideLabel(value.side()),
                    distance).getString();
        }
        return Component.translatable(LINE_KEY, described).getString();
    }

    /** The translated name of one side. */
    public static String sideLabel(PlaceAddress.Side side) {
        return switch (side) {
            case NORTH -> translate(SIDE_NORTH_KEY);
            case EAST -> translate(SIDE_EAST_KEY);
            case SOUTH -> translate(SIDE_SOUTH_KEY);
            case WEST -> translate(SIDE_WEST_KEY);
            case NONE -> "";
        };
    }

    private static String translate(String key) {
        return Component.translatable(key).getString();
    }
}
