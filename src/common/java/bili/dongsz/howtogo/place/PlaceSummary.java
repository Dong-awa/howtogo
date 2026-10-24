package bili.dongsz.howtogo.place;

/**
 * A one-line preview of a place's description.
 *
 * <p>Two questions, one answer: the text to show, and whether it is the whole description. Callers
 * that draw the preview append their own ellipsis when {@link #truncated()} is set, because a
 * tooltip and a web page want it drawn differently -- but neither may claim the preview is the
 * description when it is not, which is why the flag travels with the text rather than being
 * re-derived from its length.
 *
 * @param text      the preview, already collapsed to one line and shortened if need be
 * @param truncated whether more of the description exists than {@code text} shows
 */
public record PlaceSummary(String text, boolean truncated) {

    /** The preview with an ellipsis appended when it is not the whole description. */
    public String display() {
        return truncated && !text.isEmpty() ? text + "…" : text;
    }
}
