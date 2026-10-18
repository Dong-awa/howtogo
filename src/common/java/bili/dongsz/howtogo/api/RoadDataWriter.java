package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.road.RoadClass;

/**
 * Where a {@link RoadDataSource} puts the points and lines it wants this mod to know about.
 *
 * <h2>What a writer is, and what it is not</h2>
 * A writer is handed to {@link RoadDataSource#write} for one read, and is only valid for the duration
 * of that call: everything written is collected into a private network the mod keeps for that source,
 * and the writer is dead when the call returns. It is a way to <em>state</em> a layer, not a handle on
 * one -- nothing written here can be read back, moved or removed later through this interface.
 *
 * <h2>Nothing written here is the player's</h2>
 * Points and lines go into a network of the source's own, which the mod never saves and never offers
 * to its editor. The player's road file is a different object entirely: an addon cannot add to it,
 * cannot delete from it, and cannot be read back out of it. That separation is the whole point of this
 * interface -- a mod that could write the player's roads could also silently move them.
 *
 * <h2>Ids are the source's own</h2>
 * {@code id} strings are chosen by the source and mean nothing outside it. They are how a {@link
 * #line} names its two ends, and they have to be unique <em>within one write</em>: writing the same
 * point id twice, or a line that names a point that was never written, is a mistake in the source and
 * is reported as such rather than guessed at.
 *
 * <h2>Coordinates</h2>
 * Block coordinates, {@code y} included. A line's {@code y} is the height of its from-point: a road on
 * a slope is several lines, not one line with two heights, exactly as the mod's own editor stores it.
 */
public interface RoadDataWriter {

    /**
     * States a point.
     *
     * @param id   the source's own id for this point, unique within one write
     * @param x    block x
     * @param y    block y
     * @param z    block z
     * @param name what the point is called, or null for a nameless one
     * @param kind what kind of point it is
     */
    void point(String id, int x, int y, int z, String name, PointKind kind);

    /**
     * States a line between two points written earlier in the same call.
     *
     * <p>Straight, between the two points' coordinates: a curved road is a chain of lines, which is
     * how the mod's own editor stores a bend too.
     *
     * @param id        the source's own id for this line, unique within one write
     * @param fromPoint id of the point the line starts at
     * @param toPoint   id of the point the line ends at
     * @param roadClass what kind of road this is, which is what decides the colour it is drawn in and
     *                  the speed it is travelled at
     * @param layer     which storey it is on: 0 is the surface, positive above, negative below. This
     *                  is what makes a bridge over a road read as a bridge rather than as a junction
     * @param oneWay    true if travel is allowed only from {@code fromPoint} to {@code toPoint}
     */
    void line(String id, String fromPoint, String toPoint, RoadClass roadClass, int layer,
              boolean oneWay);
}
