package bili.dongsz.howtogo.road;

import java.util.Objects;

/**
 * One classification of a road: which kind of highway or street it is, one level below its
 * {@link RoadClass}.
 *
 * <h2>What this is now: a reference, not the definition</h2>
 * A category used to be a fixed entry in this class. It is not any more: every class has exactly one
 * built-in category -- {@link #none(RoadClass) "not classified"}, which cannot be renamed, recoloured
 * or removed -- and everything else is the player's to define. A definition (a name and a colour)
 * lives in {@code RoadCategoryStore}; a road only carries <em>which</em> of its class's categories it
 * is, which is what this type is: the pair (class, id).
 *
 * <p>That split is what lets a category be renamed without touching a single road, added without a
 * code change, and deleted while the roads that carried it fall back to "not classified": the id is
 * stable and opaque, the name and colour are looked up when the map is drawn.
 *
 * <h2>Ids</h2>
 * {@code none} is reserved for the built-in category of every class. A player-made category gets an
 * opaque id from the store ({@code c1}, {@code c2}, ...) which is never reused, so a deleted category
 * cannot come back to life on an old road. Nothing outside the store should parse or construct one
 * from the id: {@link #id()} is for the save file, and {@link #of(RoadClass, String)} is what reads
 * it back.
 */
public final class RoadCategory {

    /** The id of the built-in "not classified" category every class has. */
    public static final String NONE_ID = "none";

    private final RoadClass roadClass;
    private final String id;

    private RoadCategory(RoadClass roadClass, String id) {
        this.roadClass = roadClass;
        this.id = id;
    }

    /**
     * The "not classified" category of a class.
     *
     * <p>Not null for a real class, and the answer to every question about a road nobody has
     * classified: it is where a new road starts, where an edit that cannot be applied lands, and
     * where a road whose category has been deleted ends up.
     */
    public static RoadCategory none(RoadClass roadClass) {
        return new RoadCategory(roadClass, NONE_ID);
    }

    /**
     * The category of one class whose id is {@code id}.
     *
     * <p>An absent, blank or {@code none} id is the class's built-in category; anything else is a
     * reference to a player-made one. The reference is returned even when the store has never heard of
     * it -- a road may outlive its category, and what an unknown reference looks like is the store's
     * decision (it is drawn, and edited, as "not classified"), not this value's.
     */
    public static RoadCategory of(RoadClass roadClass, String id) {
        RoadClass resolvedClass = roadClass == null ? RoadClass.ROAD : roadClass;
        String trimmed = id == null ? "" : id.trim();
        if (trimmed.isEmpty() || NONE_ID.equalsIgnoreCase(trimmed)) {
            return none(resolvedClass);
        }
        return new RoadCategory(resolvedClass, trimmed);
    }

    /**
     * The category a save file's {@code category} field names.
     *
     * <p>The same answer as {@link #of} and kept as its own name because the storage's callers read
     * differently: a value that is missing or unreadable is not an error, it is a road nobody has
     * classified, which is exactly what an old file means.
     */
    public static RoadCategory resolve(RoadClass roadClass, String stored) {
        return of(roadClass, stored);
    }

    /** The class this category belongs to. */
    public RoadClass roadClass() {
        return roadClass;
    }

    /** The stable id this category is written as; see the class note. */
    public String id() {
        return id;
    }

    /** Whether this is the built-in "not classified" category. */
    public boolean isNone() {
        return NONE_ID.equals(id);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RoadCategory category)) {
            return false;
        }
        return roadClass == category.roadClass && id.equals(category.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roadClass, id);
    }

    @Override
    public String toString() {
        return roadClass + "/" + id;
    }
}
