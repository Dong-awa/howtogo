package bili.dongsz.howtogo.client;

/**
 * A screen that re-renders its parent underneath itself, keeping the parent visible behind it.
 * Marked on the mod's dialog screens (name prompt, line editor, place details); RoadElementRenderer asks for this marker to hold back the map's label/mark/HUD passes so they do not draw over the dialog panel.
 * Must stay in step with the drawing: declaring it without drawing the parent there suppresses the map labels for nothing.
 */
public interface ParentBackdrop {
}
