package bili.dongsz.howtogo.place;

/**
 * A one-line preview of a place's description; callers draw their own ellipsis when truncated() is set.
 * @param text the preview, shortened if needed
 * @param truncated whether more of the description exists
 */
public record PlaceSummary(String text, boolean truncated) {

    public String display() {
        return truncated && !text.isEmpty() ? text + "…" : text;
    }
}
