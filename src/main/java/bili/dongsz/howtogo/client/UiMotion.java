package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The one place this mod's interfaces get their motion from: the durations, the curves, and the
 * two primitives every animation in the mod is built out of.
 *
 * <h2>Why a motion vocabulary rather than a number at each call site</h2>
 * The same problem {@link UiTheme} solves for colour, solved for time. A screen that fades its own
 * panel over 180 ms, another that slides over 90 ms and a button that changes face the instant the
 * cursor arrives do not read as three animations; they read as three interfaces. So every duration
 * and every curve lives here as a named token -- {@link #ENTER}, {@link #HOVER}, {@link #EXPAND} and
 * the rest -- and every screen asks for one instead of writing milliseconds down. Adding a surface
 * means choosing from this list, and no surface can drift out of step, because no surface owns a
 * duration any more. The same holds for the values each token is paired with: {@link Easing} is a
 * closed set, so "silky, never linear" is a property of the class rather than of the care taken at
 * each call site.
 *
 * <h2>Two primitives, not an animation framework</h2>
 * Everything this mod animates is a number between 0 and 1 moving towards a target:
 *
 * <ul>
 *   <li>{@link Slide} -- a value that travels towards a target over a fixed duration, in either
 *       direction, and can be reversed mid-flight. Reversal is what makes it feel like an interface
 *       rather than a slideshow: the cursor leaving a button halfway through its hover-in turns the
 *       same value around from wherever it got to, instead of restarting the animation.</li>
 *   <li>{@link Easing} -- the curve read off that value. Out-curves for anything arriving (fast at
 *       first, long settle: this is what "silky" means in practice), in-out for anything that both
 *       starts and stops on screen, in-curves for anything leaving.</li>
 * </ul>
 *
 * There is deliberately no spring solver, no keyframe timeline and no scheduler: a slide is advanced
 * by the frame that reads it, which means an animation that nothing is looking at costs nothing at
 * all, and there is no state to keep in step with the render loop.
 *
 * <h2>Frame-rate independence, without asking the game what frame it is</h2>
 * A slide advances by the wall-clock time between the frames that read it, clamped to
 * {@link #MAX_STEP_SECONDS}. The clock is {@link System#nanoTime()} rather than the game's partial
 * tick because the mod's screens are not pause screens -- the world keeps running behind them -- and
 * because the delta the game reports has changed type three times across the versions this tree
 * builds for. Two calls in the same millisecond share one delta, so a screen and the HUD drawn in
 * one frame advance by one frame's worth of time rather than by two.
 *
 * <h2>Switched off</h2>
 * {@code ui_animations} is a config entry, on by default, read on every call rather than cached --
 * the same rule {@link UiTheme} follows for the palette, and for the same reason: a change takes
 * effect on the next frame with no listener and no invalidation. With it off, {@link Slide#to}
 * lands on the target in the same call and {@link #stagger} answers 1: every animation in the mod
 * becomes the instantaneous state change it replaced, and no slide is left half way through.
 */
public final class UiMotion {

    private UiMotion() {
    }

    // ------------------------------------------------------------------ tokens

    /*
     * The duration table. Read it as the mod's motion style guide rather than as six numbers: every
     * duration below is a decision about how the interface should feel, taken once, here.
     *
     * A screen arriving is 200 ms: long enough to be seen as motion rather than as a flicker, short
     * enough that a player opening a screen and clicking at once never waits for it. It is the
     * longest value in the table because it is the one thing the player did not ask to happen.
     * Leaving is faster (140 ms) -- an interface that lingers on its way out feels reluctant.
     *
     * A hover is 130 ms: the shortest span that still reads as a fade rather than a switch, and
     * short enough that sweeping the cursor across a row of buttons does not leave a trail.
     *
     * A press is 90 ms, because it has to be over before the release.
     *
     * An expand is the slowest thing the interface does (240 ms) -- a box unfolding is a change of
     * layout, and a layout that arrives too fast reads as a glitch. Its collapse is quicker
     * (190 ms) for the same reason leaving is: hiding is not something to be savoured.
     */

    /** A screen arriving: how long its sheet and its dimming take to reach full strength. */
    public static final long ENTER = 200L;
    /** A screen leaving, before the game swaps it for the next one. */
    public static final long EXIT = 140L;
    /** A button, row, chip or box changing state under the cursor. */
    public static final long HOVER = 130L;
    /** The momentary shrink a control takes while it is held down. */
    public static final long PRESS = 90L;
    /** A panel unfolding: 240 ms, the longest motion in the table. */
    public static final long EXPAND = 240L;
    /** A panel rolling back up: quicker than {@link #EXPAND}, because hiding is not the show. */
    public static final long COLLAPSE = 190L;
    /** A list travelling to its new scroll position. */
    public static final long SCROLL = 200L;
    /** One band of a HUD card appearing or disappearing (navigation starting, a trip ending). */
    public static final long HUD = 220L;
    /** How much later each successive row of a list starts arriving. */
    public static final long STAGGER = 16L;
    /** Rows past this one share the last row's delay, so a long list never trickles in for a second. */
    public static final int STAGGER_LIMIT = 8;

    /** A frame longer than this is treated as this, so a stall cannot teleport an animation. */
    private static final float MAX_STEP_SECONDS = 0.05F;
    /** Two reads closer together than this share one delta: the same frame, not two. */
    private static final long FRAME_NANOS = 1_500_000L;

    private static long frameNanos;
    private static float frameSeconds = 0.016F;

    // -------------------------------------------------------------- the switch

    /**
     * Whether the interface is allowed to animate right now.
     *
     * <p>Read on every slide, once per frame per slide, so it is left as cheap as the config read it
     * wraps: a field on the config's values object. The default is on; a player who turns it off gets
     * every animation collapsed to its end state on the next frame.
     */
    public static boolean on() {
        return RoadConfig.uiAnimations();
    }

    // ------------------------------------------------------------ frame timing

    /**
     * The time the current frame is worth, in seconds, clamped to
     * {@value #MAX_STEP_SECONDS}.
     *
     * <p>Two callers in the same millisecond get the same answer, which is what keeps a screen and
     * the HUD drawn one after the other in one frame from advancing by two frames' worth of motion.
     * The first frame after a long stall -- a world load, an alt-tab -- is worth the maximum rather
     * than the real gap: an animation that jumps to its end is less wrong than one that flickers
     * through every step at once.
     */
    public static float frameDelta() {
        long now = System.nanoTime();
        long since = now - frameNanos;
        if (since < FRAME_NANOS) {
            return frameSeconds;
        }
        frameNanos = now;
        float seconds = since / 1.0E9F;
        frameSeconds = seconds > MAX_STEP_SECONDS ? MAX_STEP_SECONDS : Math.max(0.001F, seconds);
        return frameSeconds;
    }

    /** Seconds since an arbitrary point, for the few effects that are periodic rather than a slide. */
    public static float seconds() {
        return System.nanoTime() / 1.0E9F;
    }

    /**
     * How quickly a value that has no fixed destination catches up with one, in seconds.
     *
     * <p>For a scroll position: a scroll is not a journey from A to B, it is a value chasing a
     * target that the wheel keeps moving, so it wants a follow rather than a fixed duration. Fifty-
     * five milliseconds is about {@link #SCROLL} once a wheel notch has been and gone, which is what
     * makes a wheel that is spun fast trail smoothly instead of restarting an animation per notch.
     */
    public static final float FOLLOW_SECONDS = 0.055F;

    /**
     * A value chasing a target, frame-rate independently.
     *
     * <p>Exponential rather than linear, so there is no speed to hit and no overshoot to design
     * around: the further behind, the faster it closes, which is exactly the shape a scroll wants.
     * With the animations switched off it returns the target outright, so a caller's own logic never
     * has to ask whether it was allowed to smooth.
     */
    public static float follow(float current, float target, float seconds) {
        if (!on()) {
            return target;
        }
        float remaining = target - current;
        // Under half a pixel the chase is invisible and only exists to keep rounding the same number
        // for the next dozen frames, which is what a scroll that creeps its last pixel looks like.
        // Snapping here is what keeps a wheel notch feeling like one motion instead of a slide with a
        // tail.
        if (Math.abs(remaining) < 0.5F) {
            return target;
        }
        float mine = Math.max(0.001F, seconds);
        return current + remaining * (1.0F - (float) Math.exp(-frameDelta() / mine));
    }

    // ----------------------------------------------------------------- curves

    /**
     * The curves the mod animates on.
     *
     * <p>Every one of them is non-linear on purpose -- a linear ramp is the single most common reason
     * an interface feels mechanical, because nothing in the world starts and stops at a constant
     * speed. The names are the shapes, not the use: a caller picks the shape that matches what the
     * thing is doing, and the {@code OUT_} prefixes are the ones worth reaching for by default,
     * because almost everything in a GUI is arriving and settling.
     */
    public enum Easing {

        /** Only for a value that is already being shaped by something else. */
        LINEAR {
            @Override
            public float of(float t) {
                return clamp01(t);
            }
        },

        /** Decelerating from the first frame: the workhorse for anything appearing. */
        OUT_CUBIC {
            @Override
            public float of(float t) {
                float x = 1.0F - clamp01(t);
                return 1.0F - x * x * x;
            }
        },

        /** The same shape, gentler: for a value that travels a long way. */
        OUT_QUAD {
            @Override
            public float of(float t) {
                float x = 1.0F - clamp01(t);
                return 1.0F - x * x;
            }
        },

        /**
         * Decelerating much harder: most of the distance in the first third, then a long settle.
         *
         * <p>This is the curve a screen's own entrance uses, and the reason it reads as expensive:
         * the sheet is essentially in place within 80 ms and the rest of the 200 ms is a barely
         * visible drift, which is what makes an abrupt window feel like it was always there.
         */
        OUT_QUINT {
            @Override
            public float of(float t) {
                float x = 1.0F - clamp01(t);
                return 1.0F - x * x * x * x * x;
            }
        },

        /** Accelerating to the last frame: for anything leaving, so it does not linger. */
        IN_CUBIC {
            @Override
            public float of(float t) {
                float x = clamp01(t);
                return x * x * x;
            }
        },

        /** Both ends shaped: for a value that starts and stops in full view, such as an expand. */
        IN_OUT_CUBIC {
            @Override
            public float of(float t) {
                float x = clamp01(t);
                if (x < 0.5F) {
                    return 4.0F * x * x * x;
                }
                float y = -2.0F * x + 2.0F;
                return 1.0F - y * y * y / 2.0F;
            }
        },

        /**
         * Overshoots the target by about a tenth, then settles.
         *
         * <p>For a control that is answering a press -- the tiny pop of a button released -- and
         * nowhere else: an overshoot on a panel or a screen is the one thing that makes a clean
         * interface look cheap.
         */
        OUT_BACK {
            @Override
            public float of(float t) {
                float x = clamp01(t) - 1.0F;
                float c = 1.70158F;
                return 1.0F + (c + 1.0F) * x * x * x + c * x * x;
            }
        };

        /** The curve read at a point between 0 and 1; the point is clamped first. */
        public abstract float of(float t);
    }

    // --------------------------------------------------------------- the value

    /**
     * One animated number, travelling towards a target over a fixed duration.
     *
     * <h2>Why a slider rather than a spring</h2>
     * A spring is a better model of a physical thing; a slide is a better model of an interface,
     * because the player has a budget of about 200 ms for any one motion and a spring cannot be told
     * that. A slide can, and the one property a slide needs beyond a duration -- being interruptible
     * from wherever it currently is -- falls out of storing the value rather than the time: setting
     * the target to the other end mid-flight turns the same value around, so the motion is always
     * continuous and never restarts.
     *
     * <h2>Idempotent within a frame</h2>
     * {@link #raw()} and {@link #eased()} both advance the value, and a caller is free to ask for
     * both in one frame (drawing a panel's opacity and its offset, say) without the animation running
     * at double speed: a slide that has already stepped within {@link #FRAME_NANOS} returns the
     * value it has.
     *
     * <h2>Not thread safe, and not meant to be</h2>
     * Every slide is owned by the screen or widget that draws it and read only from the render
     * thread. There is no synchronisation here on purpose: a lock per slide per frame would cost
     * more than the animations are worth.
     */
    public static final class Slide {

        private final Easing easing;
        private float value;
        private float target;
        private long durationMillis;
        private long steppedNanos;

        /** A slide at rest at {@code start}, for {@code duration} milliseconds, on {@code easing}. */
        public Slide(Easing easing, float start, long durationMillis) {
            this.easing = easing;
            this.value = clamp01(start);
            this.target = this.value;
            this.durationMillis = Math.max(1L, durationMillis);
        }

        /** A slide resting at zero; the common beginning. */
        public static Slide of(Easing easing, long durationMillis) {
            return new Slide(easing, 0.0F, durationMillis);
        }

        /** The same slide with a new duration, for a reuse that has to be quicker or slower. */
        public Slide duration(long millis) {
            this.durationMillis = Math.max(1L, millis);
            return this;
        }

        /** The curve this slide reads on. */
        public Easing easing() {
            return easing;
        }

        /**
         * Sends the value to {@code to}, from wherever it currently is.
         *
         * <p>The target is clamped, so a caller that passes a value from a computation cannot send a
         * slide out of range and leave the easing curve reading nonsense. With the interface's
         * animations switched off this is a jump rather than a start: the state the player asked for
         * is the state they see, in the frame they asked for it.
         */
        public Slide to(float to) {
            this.target = clamp01(to);
            if (!on()) {
                this.value = this.target;
            }
            return this;
        }

        /** The far end: {@code to(1)} in a form that reads as what it means at a call site. */
        public void open() {
            to(1.0F);
        }

        /** The near end: {@code to(0)}. */
        public void close() {
            to(0.0F);
        }

        /** Puts the value and the target both at {@code at}, with no motion at all. */
        public Slide jump(float at) {
            this.value = clamp01(at);
            this.target = this.value;
            return this;
        }

        /** Whether the value has reached its target to within a hundredth. */
        public boolean settled() {
            return Math.abs(target - value) < 0.01F;
        }

        /** Where the value is now, as a number between 0 and 1, before the curve. */
        public float raw() {
            advance();
            return value;
        }

        /** Where the value is now, read through the slide's own curve. */
        public float eased() {
            advance();
            return easing.of(value);
        }

        /** The linear travel this slide has made in the last frame; for a caller doing its own curve. */
        public float step(float amount) {
            advance();
            return easing.of(value) * amount;
        }

        private void advance() {
            if (!on()) {
                value = target;
                return;
            }
            long now = System.nanoTime();
            if (now - steppedNanos < FRAME_NANOS) {
                return;
            }
            steppedNanos = now;
            if (value == target) {
                return;
            }
            float travel = frameDelta() * 1000.0F / durationMillis;
            if (value < target) {
                value = Math.min(target, value + travel);
            } else {
                value = Math.max(target, value - travel);
            }
        }
    }

    /**
     * A pool of slides owned by index, for a list whose rows are redrawn from a loop rather than
     * held as objects.
     *
     * <p>Indexed rather than keyed by the row's identity on purpose: what these animate is a hover
     * highlight, and a highlight that lands on the row now in that position after a list re-sorts is
     * a highlight the cursor is under. A pool that outlives the list is also the thing that keeps
     * this allocation-free after the first pass over a list, which matters because it is read once
     * per row per frame.
     */
    public static final class Slides {

        private Slide[] slides = new Slide[0];

        /** The slide at {@code index}, made at rest on first use. */
        public Slide get(int index) {
            return get(index, Easing.OUT_CUBIC, HOVER);
        }

        /** The slide at {@code index}, made on first use with the given shape. */
        public Slide get(int index, Easing easing, long durationMillis) {
            if (index < 0) {
                return new Slide(easing, 0.0F, durationMillis);
            }
            if (index >= slides.length) {
                Slide[] grown = new Slide[Math.max(index + 1, slides.length * 2)];
                System.arraycopy(slides, 0, grown, 0, slides.length);
                slides = grown;
            }
            Slide slide = slides[index];
            if (slide == null) {
                slide = new Slide(easing, 0.0F, durationMillis);
                slides[index] = slide;
            }
            return slide;
        }

        /** Forgets every slide, for a list that has been rebuilt rather than re-sorted. */
        public void clear() {
            for (int i = 0; i < slides.length; i++) {
                if (slides[i] != null) {
                    slides[i].jump(0.0F);
                }
            }
        }
    }

    // --------------------------------------------------------------- the screen

    /*
     * Screens animate in and out through a state kept per screen instance. The map is what lets
     * Screens defer a swap by a hundred and forty milliseconds without every screen having to hold a
     * field for it: a screen registers itself the first time it draws, and Screens asks the same map
     * whether the screen on screen is one of the mod's own.
     *
     * Weakly keyed, because a screen's animation state should live exactly as long as the screen
     * does. Two of the mod's screens draw their parent underneath them, so a state that was dropped
     * when a child was installed would make the parent fade in again behind its own child; and a
     * state that was never dropped would keep every picker the player had opened for the whole
     * session. Letting the garbage collector decide is the only rule that is right in both cases.
     * Screens do not override equals, so this behaves as the identity map it reads like.
     */
    private static final Map<Screen, ScreenState> STATES = new WeakHashMap<>();

    /**
     * The animation state of one screen: opening, open, closing.
     *
     * <p>Two slides rather than one, because the two motions have different curves: arriving is an
     * {@link Easing#OUT_QUINT} that is nearly done in a third of its time, leaving is an
     * {@link Easing#IN_CUBIC} that starts slowly and is over quickly. A single slide turned around
     * would have to pick one of the two curves for both directions.
     */
    public static final class ScreenState {

        private final Slide enter = new Slide(Easing.OUT_QUINT, 0.0F, ENTER);
        private final Slide exit = new Slide(Easing.IN_CUBIC, 0.0F, EXIT);
        private boolean leaving;

        /** How far this screen has opened, 0 to 1, on its entrance curve. */
        public float opened() {
            return enter.eased();
        }

        /** How far this screen has closed, 0 to 1; zero for a screen that is staying. */
        public float closed() {
            return leaving ? exit.eased() : 0.0F;
        }

        /**
         * How far this screen's whole frame is displaced this frame, in pixels, downwards positive.
         *
         * <p>Two separate travels rather than one number: arriving rises into place from below at
         * {@code rise}, and leaving lifts away by {@code fall}. A single value would have to pick one
         * direction for both, and an exit that sank downwards reads as the panel being dropped.
         */
        public float offsetY(float rise, float fall) {
            return (1.0F - enter.eased()) * rise - exit.eased() * fall;
        }

        /**
         * The opacity everything on this screen is drawn at: the entrance and the exit multiplied
         * together, so a screen that is opened and then immediately closed fades from wherever the
         * opening got to rather than flickering back to full.
         */
        public float opacity() {
            return clamp01(enter.eased() * (1.0F - closed()));
        }

        /** Whether this screen is on its way out. */
        public boolean leaving() {
            return leaving;
        }

        /** Starts the exit; the screen draws at a falling opacity until the game swaps it. */
        void leave() {
            leaving = true;
            exit.jump(0.0F);
            exit.to(1.0F);
        }

        /** Whether the exit has finished, which is when the deferred swap may happen. */
        boolean left() {
            return leaving && exit.settled() && exit.raw() > 0.99F;
        }

        /** Puts the screen back at the start of its entrance, for an instance being shown again. */
        void reopen() {
            leaving = false;
            enter.jump(0.0F);
            enter.to(1.0F);
            exit.jump(0.0F);
        }

        /** No motion at all: fully open and staying. */
        void settleOpen() {
            leaving = false;
            enter.jump(1.0F);
            exit.jump(0.0F);
        }
    }

    /**
     * The animation state of a screen, made on first use.
     *
     * <p>Also the per-frame pump the deferred swap needs: a screen that is still drawing is still
     * animating out, and the frame in which its exit finishes is the frame in which {@link Screens}
     * is allowed to install the screen it was asked for.
     */
    public static ScreenState of(Screen screen) {
        if (screen == null) {
            return NO_SCREEN;
        }
        ScreenState state = STATES.get(screen);
        if (state == null) {
            state = new ScreenState();
            state.enter.to(1.0F);
            STATES.put(screen, state);
        }
        // Only the screen the game is actually showing may set the ambient opacity. Two of the mod's
        // screens draw their parent underneath themselves, and a parent that published its own
        // opacity over its child's would leave the child's buttons at full strength while the rest of
        // it faded out.
        if (screen == Screens.current()) {
            activeOpacity = state.opacity();
        }
        Screens.pump();
        return state;
    }

    /*
     * The opacity of the screen that is drawing, kept here rather than passed down to every helper.
     *
     * A button does not know which screen it is on and should not have to be told: it is drawn from
     * inside that screen's render pass, one frame after {@link #of} was read for it. Making it read
     * this instead of taking a parameter is what lets the mod's buttons fade with their sheets
     * without a new argument on the one method nine screens call. Reading it costs a field read, and
     * the answer is 1 when nothing is animating, which is the state the interface spends its life in.
     */
    private static float activeOpacity = 1.0F;

    /** The opacity of the screen that is drawing right now, 0 to 1; 1 when nothing is animating. */
    public static float activeOpacity() {
        return activeOpacity;
    }

    // ------------------------------------------------------------- displacement

    /*
     * Where the interface moves, and why it moves rather than only fading.
     *
     * A fade alone is not motion: the eye reads a sheet that appears in place as a cut, and a cut is
     * what makes an interface feel assembled rather than designed. Ten pixels of travel over the same
     * two hundred milliseconds is enough to read as movement and small enough that nothing inside the
     * panel has to be clipped or re-laid-out -- which matters, because the game's scissors cannot be
     * nested and every clip added here is a clip some other drawing pass would have to unwind.
     *
     * The travel is a whole-frame translation of the screen's own drawing: the sheet, its text, its
     * map and its buttons move together, which is the one form of displacement that needs no clip at
     * all. Arriving rises into place from below; leaving lifts away.
     */

    /** How far a screen travels into place, in pixels. */
    public static final float RISE = 10.0F;
    /** How far a screen travels away again, in pixels. */
    public static final float FALL = 6.0F;
    /** How far a HUD card travels into its corner. Smaller, because it is a card, not a window. */
    public static final float HUD_RISE = 6.0F;

    /**
     * How far past each screen edge a scrim has to reach, in pixels.
     *
     * <p>A scrim is drawn from the frame's own corner, and the frame is translated by up to
     * {@link #RISE} while it arrives -- so without a margin the dimming would come with an undimmed
     * strip along one edge for the length of the entrance. Thirty-two is comfortably more than the
     * largest travel in this class and costs nothing: the pixels are outside the screen.
     */
    public static final int SCRIM_MARGIN = 32;

    /**
     * Pushes the frame's displacement onto the game's own matrix.
     *
     * <p>One fenced helper instead of nine: 1.21.6 replaced the {@code PoseStack} a GUI drew on with
     * a {@code Matrix3x2fStack}, so the two eras push, translate and pop with different names. The
     * rest of the mod's drawing code asks this and never sees the difference.
     *
     * <p>Every caller must pair this with {@link #popFrame} on the same frame. An unbalanced push
     * cannot outlive the frame -- the game builds a fresh graphics object for each one -- but it would
     * still misplace everything drawn after the screen, so the pairing is not optional.
     */
    public static void pushFrame(GuiGraphics graphics, float dx, float dy) {
        //? if >=1.21.6 {
        /*graphics.pose().pushMatrix();
        graphics.pose().translate(dx, dy);
        *///?} else {
        graphics.pose().pushPose();
        graphics.pose().translate(dx, dy, 0.0F);
        //?}
        int next = Math.min(frameDepth + 1, FRAME_OFFSETS.length - 1);
        FRAME_OFFSETS[next] = FRAME_OFFSETS[Math.min(frameDepth, FRAME_OFFSETS.length - 1)] + dy;
        frameDepth = next;
    }

    /** Pops a displacement pushed by {@link #pushFrame}. */
    public static void popFrame(GuiGraphics graphics) {
        //? if >=1.21.6 {
        /*graphics.pose().popMatrix();
        *///?} else {
        graphics.pose().popPose();
        //?}
        frameDepth = Math.max(0, frameDepth - 1);
    }

    /*
     * The displacement in force, mirrored outside the game's matrix so that a scissor can follow it.
     *
     * The two are not the same thing: a pose translation moves what a fill or a string lands on, while
     * the game's scissor is set in screen pixels and ignores the pose entirely. A list clipped to its
     * own rectangle and then translated by a frame would slide out from under its clip -- the bottom
     * row sheared off and a gap at the top -- which is exactly the seam this bookkeeping removes. A
     * stack, because the frame, a card drawn into it and a HUD pass can each add their own.
     */
    private static final float[] FRAME_OFFSETS = new float[8];
    private static int frameDepth;

    /** How far the drawing in force is displaced vertically, in pixels. */
    public static float frameOffsetY() {
        return FRAME_OFFSETS[Math.min(frameDepth, FRAME_OFFSETS.length - 1)];
    }

    /**
     * Clips to a rectangle in the coordinates the caller is drawing in, displaced like the frame.
     *
     * <p>The replacement for {@code graphics.enableScissor} inside a displaced frame, and the reason
     * it exists: the game's scissor takes screen pixels and knows nothing about a translated pose, so
     * a clipped list inside a rising panel would be clipped in the wrong place for as long as the
     * panel is moving. Every caller still pairs it with {@code graphics.disableScissor()}.
     */
    public static void clip(GuiGraphics graphics, int x1, int y1, int x2, int y2) {
        int dy = Math.round(frameOffsetY());
        graphics.enableScissor(x1, y1 + dy, x2, y2 + dy);
    }

    /** A state for a caller with no screen to animate, so a null never has to be tested twice. */
    private static final ScreenState NO_SCREEN = new ScreenState();

    static {
        NO_SCREEN.settleOpen();
    }

    /** Whether this screen is one the mod animates, which is what decides if a swap may be deferred. */
    static boolean tracked(Screen screen) {
        return screen != null && STATES.containsKey(screen);
    }

    /** Starts {@code screen} leaving, and answers whether it was a screen the mod could animate. */
    static boolean beginLeave(Screen screen) {
        if (!tracked(screen)) {
            return false;
        }
        STATES.get(screen).leave();
        return true;
    }

    /** Whether the screen's exit animation has finished. */
    static boolean left(Screen screen) {
        ScreenState state = screen == null ? null : STATES.get(screen);
        return state == null || state.left();
    }

    /** Puts a screen that is about to be shown at the start of its entrance. */
    static void reopen(Screen screen) {
        if (screen == null) {
            return;
        }
        ScreenState state = STATES.get(screen);
        if (state == null) {
            state = new ScreenState();
            STATES.put(screen, state);
        }
        state.reopen();
    }

    /** Drops a screen's state, for a screen the game has finished with. */
    static void forget(Screen screen) {
        if (screen != null) {
            STATES.remove(screen);
        }
    }

    // -------------------------------------------------------------- the colours

    /** A colour with its own alpha multiplied by {@code factor}, for a surface fading in or out. */
    public static int fade(int argb, float factor) {
        if (factor >= 0.999F) {
            return argb;
        }
        int alpha = (int) (((argb >>> 24) & 0xFF) * clamp01(factor));
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }

    /** A colour with its alpha set outright, which is what a scrim's own ramp asks for. */
    public static int withAlpha(int argb, float alpha) {
        int a = (int) (clamp01(alpha) * 255.0F + 0.5F);
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    /**
     * Two colours mixed, including their alphas.
     *
     * <p>This is how a control changes state over time rather than in a step: the hover colour is
     * mixed into the resting colour by the hover slide, so the face is at every value between the two
     * on the way. Mixing alpha as well as RGB is deliberate -- a tint laid over a panel is a colour
     * with a low alpha, and interpolating only its RGB would fade it through fully opaque greys.
     */
    public static int mix(int from, int to, float amount) {
        float t = clamp01(amount);
        if (t <= 0.0F) {
            return from;
        }
        if (t >= 1.0F) {
            return to;
        }
        int a = channel(from >>> 24, to >>> 24, t);
        int r = channel(from >>> 16, to >>> 16, t);
        int g = channel(from >>> 8, to >>> 8, t);
        int b = channel(from, to, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int channel(int from, int to, float t) {
        int was = from & 0xFF;
        int now = to & 0xFF;
        return (int) (was + (now - was) * t + 0.5F) & 0xFF;
    }

    /**
     * The share of the way from a resting state to its hovered one, for a mixed colour.
     *
     * <p>Small on purpose: {@link #mix} is applied to whole surfaces here, and a hover that changed a
     * panel's face by a fifth would read as a different surface rather than the same surface under
     * the cursor. A button's own two faces are already only a shade apart; this is the hand that moves
     * between them.
     */
    public static int hovered(int resting, int target, float amount) {
        return mix(resting, target, amount);
    }

    // ----------------------------------------------------------------- the time

    /**
     * How long one row takes to arrive, whatever its place in the queue.
     *
     * <p>Constant on purpose. The obvious stagger -- subtract a delay from the elapsed time and
     * divide by the whole entrance -- gives the later rows less and less time to arrive, so with
     * {@link #ENTER} at 200 ms and eight delays of {@link #STAGGER}, the ninth row would finish the
     * entrance at three quarters of its opacity and stay there: a list whose bottom half is
     * permanently dimmed. Keeping the per-row span fixed and squeezing the delays into what is left
     * means every row arrives fully, and the last one still arrives when the screen does.
     */
    private static final long ROW_FADE = 120L;

    /**
     * The progress of one row of a list that is arriving, given how far the whole list has got.
     *
     * <p>A stagger is what turns a list appearing into a list being revealed: each row starts a
     * little after the one above it, so the eye is led down the panel instead of being shown a wall.
     * Past {@link #STAGGER_LIMIT} rows the delay stops growing, which keeps a forty-row picker from
     * trickling in for the better part of a second; the rows past the limit arrive together, which
     * nobody notices because they are the ones furthest down.
     *
     * <p>The delay between rows is {@link #STAGGER} squeezed to fit: the rows have to be spread over
     * what the entrance has left after {@link #ROW_FADE}, so a shorter entrance tightens the spacing
     * rather than leaving the last rows half arrived. At the tokens above that is 10 ms a row over
     * eight rows, 120 ms each, everything in place at {@link #ENTER}.
     *
     * @param index   the row's position in the list
     * @param overall how far the screen's own entrance has got, 0 to 1
     */
    public static float stagger(int index, float overall) {
        if (!on()) {
            return 1.0F;
        }
        int row = Math.min(Math.max(0, index), STAGGER_LIMIT);
        long spare = ENTER - ROW_FADE;
        long step = STAGGER_LIMIT > 0 ? Math.min(STAGGER, spare / STAGGER_LIMIT) : 0L;
        float elapsed = clamp01(overall) * ENTER - row * step;
        return Easing.OUT_CUBIC.of(clamp01(elapsed / ROW_FADE));
    }

    /** A colour for a row that is arriving, faded by its own share of the stagger. */
    public static int staggerFade(int argb, int index, float overall) {
        return fade(argb, stagger(index, overall));
    }

    /** Clamps to the range a curve is defined on. */
    public static float clamp01(float t) {
        return t < 0.0F ? 0.0F : (t > 1.0F ? 1.0F : t);
    }

    /** The same, for a value that may be outside the range in either direction. */
    public static float clamp(float t, float min, float max) {
        return t < min ? min : (t > max ? max : t);
    }
}
