package bili.dongsz.howtogo.api;

import bili.dongsz.howtogo.road.RoadClass;

/**
 * Where a RoadDataSource states the points and lines it contributes, valid only for one write and one call: writes go into a per-source private network the mod never saves, while the player's road file is a different object an addon cannot read, add to or delete.
 * Ids are chosen by the source and unique within one write; a duplicate or a dangling point reference is reported, not guessed. Coordinates are block coordinates including y, and a line's y is its from-point's height, so a slope is several lines.
 */
public interface RoadDataWriter {

    /**
     * States a point.
     * @param id the source's own id for this point, unique within one write
     * @param x block x
     * @param y block y
     * @param z block z
     * @param name what the point is called, or null for a nameless one
     * @param kind what kind of point it is
     */
    void point(String id, int x, int y, int z, String name, PointKind kind);

    /**
     * States a straight line between two points written earlier in the same call; a bend is a chain of lines.
     * @param id the source's own id for this line, unique within one write
     * @param fromPoint id of the point the line starts at
     * @param toPoint id of the point the line ends at
     * @param roadClass grade, which decides the drawn colour and the travel speed
     * @param layer storey: 0 surface, positive above, negative below. This is what makes a bridge over a road read as a bridge rather than as a junction
     * @param oneWay true if travel is allowed only from fromPoint to toPoint
     */
    void line(String id, String fromPoint, String toPoint, RoadClass roadClass, int layer,
              boolean oneWay);
}
