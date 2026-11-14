package bili.dongsz.howtogo.road;

import java.util.Objects;

/**
 * One classification of a road, one level below its RoadClass, carried as the pair (class, id).
 * Every class has one built-in, non-removable not-classified category (NONE_ID); player-made ones carry an opaque id from RoadCategoryStore (c1, c2, ...) that is never reused. The definition -- name and colour -- lives in the store and is looked up when the map is drawn, so renaming a category does not touch roads.
 * An unknown id is not an error: the store draws and edits it as not classified.
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
     * The built-in not-classified category of a class; never null for a real class. New roads start here and unknown categories land here.
     */
    public static RoadCategory none(RoadClass roadClass) {
        return new RoadCategory(roadClass, NONE_ID);
    }

    /**
     * The category of one class with the given id. A blank or none id is the built-in category; an unknown player-made id is still returned, and the store decides how it looks.
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
     * The category a save file's field names; same as of(), but a missing or unreadable value is not an error.
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
