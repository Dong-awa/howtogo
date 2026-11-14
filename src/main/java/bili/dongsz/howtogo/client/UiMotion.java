package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.RoadConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The one place this mod's interfaces get their motion: the durations, the curves, and the two primitives every animation is built from.
 * Every duration and curve is a named token here (ENTER, HOVER, EXPAND and the rest) rather than milliseconds at each call site, so screens cannot drift out of step; Easing is a closed set, so "never linear" is a property of this class rather than of the care taken at each call site.
 * Two primitives, not a framework: Slide is a 0..1 value travelling to a target over a fixed duration and reversible mid-flight; Easing is the curve read off it (out for arriving, in-out for both, in for leaving). No spring, keyframe timeline or scheduler -- a slide advances only in the frame that reads it, so an unobserved animation costs nothing.
 * Frame-rate independence: a slide advances by wall-clock time since the last read, clamped to MAX_STEP_SECONDS. The clock is System.nanoTime() because mod screens are not pause screens and the game's delta type has changed three times across the supported versions. Reads in the same millisecond share one delta, so a screen and the HUD in one frame advance once.
 * ui_animations is read on every call rather than cached, so a change applies next frame. Off, Slide#to lands on the target immediately and stagger answers 1, collapsing every animation to the state change it replaced.
 */
public final class UiMotion {

    private UiMotion() {
    }


    /*
     * The duration table, read as the mod's motion style guide rather than as six numbers: each duration is a decision about how the interface should feel, taken once here.
     * ENTER is 200 ms -- long enough to read as motion, short enough that a player opening a screen and clicking at once never waits; EXIT is faster (140 ms) because lingering feels reluctant. HOVER is 130 ms, PRESS 90 ms (over before the release), EXPAND 240 ms (a layout change that arrives too fast reads as a glitch) and COLLAPSE 190 ms.
     */

    /** A screen arriving: how long its sheet and dimming take to reach full strength. */
    public static final long ENTER = 200L;
    /** A screen leaving, before the game swaps it for the next one. */
    public static final long EXIT = 140L;
    /** A button, row, chip or box changing state under the cursor. */
    public static final long HOVER = 130L;
    /** The momentary shrink a control takes while it is held down. */
    public static final long PRESS = 90L;
    /** A panel unfolding, the longest motion in the table. */
    public static final long EXPAND = 240L;
    /** A panel rolling back up: quicker than EXPAND. */
    public static final long COLLAPSE = 190L;
    /** A list travelling to its new scroll position. */
    public static final long SCROLL = 200L;
    /** One band of a HUD card appearing or disappearing. */
    public static final long HUD = 220L;
    /** How much later each successive row of a list starts arriving. */
    public static final long STAGGER = 16L;
    /** Rows past this share the last row's delay, so a long list never trickles in for a second. */
    public static final int STAGGER_LIMIT = 8;

    /**
     * How faint one page's content gets while another takes over, as an opacity.
     * Three tenths is the whole transition: at zero the pages fade through a blank surface (the interface blinking), at one there is no transition; at 0.3 the outgoing page has visibly left and the incoming has visibly not arrived.
     * Content only: the sheet and the dimming stay at full (see ScreenState#surface()), because they are the surface the two pages share.
     */
    public static final float HANDOFF_FLOOR = 0.3F;

    /** A frame longer than this is treated as this, so a stall cannot teleport an animation. */
    private static final float MAX_STEP_SECONDS = 0.05F;
    /** Two reads closer together than this share one delta: the same frame, not two. */
    private static final long FRAME_NANOS = 1_500_000L;

    private static long frameNanos;
    private static float frameSeconds = 0.016F;


    /**
     * Whether the interface may animate right now.
     * Read per slide per frame, so it is as cheap as the config read it wraps; off collapses every animation to its end state on the next frame.
     */
    public static boolean on() {
        return RoadConfig.uiAnimations();
    }


    /**
     * The time the current frame is worth, in seconds, clamped to MAX_STEP_SECONDS.
     * Two callers in the same millisecond get the same answer, so a screen and the HUD in one frame do not advance two frames. The first frame after a stall is worth the maximum rather than the real gap: a jump to the end is less wrong than a flicker through every step.
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

    /** Seconds since an arbitrary point, for effects that are periodic rather than slides. */
    public static float seconds() {
        return System.nanoTime() / 1.0E9F;
    }

    /**
     * How quickly a value with no fixed destination catches up, in seconds.
     * For a scroll position: the wheel keeps moving the target, so the value follows rather than travels. Fifty-five milliseconds is about SCROLL once a notch has gone, which makes a fast spin trail smoothly instead of restarting per notch.
     */
    public static final float FOLLOW_SECONDS = 0.055F;

    /**
     * A value chasing a target, frame-rate independently.
     * Exponential, so there is no speed to hit and no overshoot to design around. With animations off it returns the target, so a caller never has to ask whether smoothing was allowed.
     */
    public static float follow(float current, float target, float seconds) {
        if (!on()) {
            return target;
        }
        float remaining = target - current;
        // Under half a pixel the chase is invisible and only keeps rounding the same number
        if (Math.abs(remaining) < 0.5F) {
            return target;
        }
        float mine = Math.max(0.001F, seconds);
        return current + remaining * (1.0F - (float) Math.exp(-frameDelta() / mine));
    }

    /**
     * A value chasing a target with its own time constant, for a unit that is not a 0..1 fade.
     * follow is the same chase sized for a scroll; a map camera moves in world blocks and zooms logarithmically, so it needs its own constant and its own idea of close enough.
     */
    public static double chase(double current, double target, float seconds, double snap) {
        if (!on()) {
            return target;
        }
        double remaining = target - current;
        if (Math.abs(remaining) <= snap) {
            return target;
        }
        float mine = Math.max(0.001F, seconds);
        return current + remaining * (1.0 - Math.exp(-frameDelta() / mine));
    }


    /**
     * The curves the mod animates on.
     * Every one is non-linear on purpose: a linear ramp is the commonest reason an interface feels mechanical. OUT_ curves are the default, because almost everything in a GUI is arriving and settling.
     */
    public enum Easing {

        /** Only for a value already being shaped by something else. */
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
         * This is the curve a screen's entrance uses -- essentially in place within 80 ms, with the rest a barely visible drift.
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
         * Overshoots the target by about a tenth, then settles. Only for a control answering a press; an overshoot on a panel looks cheap.
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


    /**
     * One animated number, travelling towards a target over a fixed duration.
     * A slide rather than a spring: the player has a budget of about 200 ms for any one motion and a spring cannot be told that. Being interruptible falls out of storing the value rather than the time -- a new target mid-flight turns the same value around, so motion is continuous and never restarts.
     * Idempotent within a frame: raw() and eased() both advance, but a slide that already stepped within FRAME_NANOS returns the value it has, so asking twice in one frame does not double the speed.
     * Not thread safe: every slide is owned by the screen or widget that draws it and read from the render thread only.
     */
    public static final class Slide {

        private final Easing easing;
        private float value;
        private float target;
        private long durationMillis;
        private long steppedNanos;

        /** A slide at rest at start, for duration milliseconds, on easing. */
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

        /** The same slide with a new duration, for a reuse that must be quicker or slower. */
        public Slide duration(long millis) {
            this.durationMillis = Math.max(1L, millis);
            return this;
        }

        /** The curve this slide reads on. */
        public Easing easing() {
            return easing;
        }

        /**
         * Sends the value to to, from wherever it currently is.
         * The target is clamped, so a computed argument cannot send the curve out of range. With the interface's animations off this is a jump, so the state asked for is the state seen, in the same frame.
         */
        public Slide to(float to) {
            this.target = clamp01(to);
            if (!on()) {
                this.value = this.target;
            }
            return this;
        }

        /** The far end: to(1), in a form that reads as what it means at a call site. */
        public void open() {
            to(1.0F);
        }

        /** The near end: to(0). */
        public void close() {
            to(0.0F);
        }

        /** Puts the value and the target both at at, with no motion. */
        public Slide jump(float at) {
            this.value = clamp01(at);
            this.target = this.value;
            return this;
        }

        /** Whether the value has reached its target to within a hundredth. */
        public boolean settled() {
            return Math.abs(target - value) < 0.01F;
        }

        /** Where the value is now, 0 to 1, before the curve. */
        public float raw() {
            advance();
            return value;
        }

        /** Where the value is now, read through the slide's own curve. */
        public float eased() {
            advance();
            return easing.of(value);
        }

        /** The linear travel this slide made in the last frame; for a caller doing its own curve. */
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
     * A pool of slides owned by index, for a list whose rows are redrawn from a loop.
     * Indexed rather than keyed by row identity because what these animate is a hover highlight, and a highlight must land on the row now in that position after a re-sort. The pool also keeps the list allocation-free after the first pass, which matters because it is read once per row per frame.
     */
    public static final class Slides {

        private Slide[] slides = new Slide[0];

        /** The slide at index, made at rest on first use. */
        public Slide get(int index) {
            return get(index, Easing.OUT_CUBIC, HOVER);
        }

        /** The slide at index, made on first use with the given shape. */
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


    /*
     * Screens animate in and out through a state kept per screen instance. The map lets Screens defer a swap by a hundred and forty milliseconds without every screen holding a field: a screen registers itself the first time it draws, and Screens asks the same map whether the screen on screen is one of the mod's own.
     * Weakly keyed, because state should live exactly as long as its screen: two of the mod's screens draw their parent underneath, so dropping a child's state early would make the parent fade in again behind it, while never dropping it would keep every picker opened for the session. Screens do not override equals, so this behaves as an identity map.
     */
    private static final Map<Screen, ScreenState> STATES = new WeakHashMap<>();

    /**
     * The animation state of one screen: opening, open, closing.
     * Two slides rather than one because the motions have different curves -- OUT_QUINT arriving, IN_CUBIC leaving; a single turned-around slide would have to pick one for both directions.
     */
    public static final class ScreenState {

        private final Slide enter = new Slide(Easing.OUT_QUINT, 0.0F, ENTER);
        private final Slide exit = new Slide(Easing.IN_CUBIC, 0.0F, EXIT);
        private boolean leaving;
        /**
         * Whether the transition in flight is a hand-off to or from another of this mod's pages.
         * Set by the entry points, never inferred from the slides: it is a statement about the swap that durations cannot express. See surface(), opacity() and Screens.
         */
        private boolean handoff;

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
         * Two travels rather than one: arriving rises into place, leaving lifts away. A single value would have to pick one direction for both, and an exit that sank downwards reads as the panel being dropped.
         * A hand-off does not travel at all: both pages keep their sheet and only the content changes, so moving either would put a visible step in a transition whose point is that nothing moves.
         */
        public float offsetY(float rise, float fall) {
            if (handoff) {
                return 0.0F;
            }
            return (1.0F - enter.eased()) * rise - exit.eased() * fall;
        }

        /**
         * The opacity everything on this screen is drawn at: entrance and exit multiplied, so a screen opened and immediately closed fades from wherever the opening got to.
         * In a hand-off the content stops at HANDOFF_FLOOR and the arriving page picks the fade up, so the halves read as one cross-fade.
         */
        public float opacity() {
            float entered = enter.eased();
            if (!handoff) {
                return clamp01(entered * (1.0F - closed()));
            }
            if (leaving) {
                return clamp01(entered * (1.0F - closed() * (1.0F - HANDOFF_FLOOR)));
            }
            return clamp01(HANDOFF_FLOOR + entered * (1.0F - HANDOFF_FLOOR));
        }

        /**
         * How opaque the screen's own surface is: the dimming over the world and the sheet.
         * Same as opacity() except during a hand-off, where the surface does not fade at all -- fading it would show the world between two pages. Content still cross-fades on top, so there is motion without a hole in the middle.
         * An arriving hand-off page holds it from its first frame, because the page it replaces left the surface standing at full.
         */
        public float surface() {
            if (!handoff) {
                return opacity();
            }
            return leaving ? enter.eased() : 1.0F;
        }

        /** Whether this screen is on its way out. */
        public boolean leaving() {
            return leaving;
        }

        /** Starts the exit; the screen draws at a falling opacity until the game swaps it. */
        void leave() {
            handoff = false;
            leaving = true;
            exit.jump(0.0F);
            exit.to(1.0F);
        }

        /**
         * Starts the exit of a page that another of this mod's pages is taking over.
         * The same slide as leave(), read differently: content drops to HANDOFF_FLOOR, the surface stays and the frame does not travel. See Screens#defer.
         */
        void handOffOut() {
            handoff = true;
            leaving = true;
            exit.jump(0.0F);
            exit.to(1.0F);
        }

        /**
         * Starts the entrance of a page taking over from another.
         * From the content floor rather than from nothing, with the surface already up, so the arriving page finishes a fade the leaving page started. The exit slide is reset so a previous departure cannot bleed into this arrival.
         */
        void handOffIn() {
            handoff = true;
            leaving = false;
            enter.jump(0.0F);
            enter.to(1.0F);
            exit.jump(0.0F);
        }

        /** Whether the transition in flight is a hand-off; read once by Screens#pump(). */
        boolean handoff() {
            return handoff;
        }

        /** Whether the exit has finished, which is when the deferred swap may happen. */
        boolean left() {
            return leaving && exit.settled() && exit.raw() > 0.99F;
        }

        /** No motion at all: fully open and staying. */
        void settleOpen() {
            handoff = false;
            leaving = false;
            enter.jump(1.0F);
            exit.jump(0.0F);
        }
    }

    /**
     * The animation state of a screen, made on first use.
     * Also the per-frame pump the deferred swap needs: a screen still drawing is still animating out, and the frame its exit finishes is the frame Screens may install the screen it was asked for.
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
        // Only the screen the game is actually showing may set the ambient opacity.
        if (screen == Screens.current()) {
            activeOpacity = state.opacity();
        }
        Screens.pump();
        return state;
    }

    /*
     * The opacity of the screen that is drawing, kept here rather than passed down to every helper.
     * A button does not know which screen it is on and is drawn from inside that screen's render pass a frame after of() was read; reading this instead of taking a parameter lets buttons fade with their sheets without a new argument on the one method nine screens call. It costs a field read and is 1 when nothing is animating.
     */
    private static float activeOpacity = 1.0F;

    /** The opacity of the screen that is drawing right now, 0 to 1; 1 when nothing is animating. */
    public static float activeOpacity() {
        return activeOpacity;
    }


    /*
     * Where the interface moves, and why it moves rather than only fading.
     * A fade alone is not motion: a sheet that appears in place reads as a cut, which makes an interface feel assembled rather than designed. Ten pixels of travel over two hundred milliseconds reads as movement and is small enough that nothing has to be clipped or re-laid-out -- and the game's scissors cannot nest.
     * The travel is a whole-frame translation of the screen's own drawing -- sheet, text, map and buttons move together -- the one form of displacement that needs no clip at all. Arriving rises from below; leaving lifts away.
     */

    /** How far a screen travels into place, in pixels. */
    public static final float RISE = 10.0F;
    /** How far a screen travels away again, in pixels. */
    public static final float FALL = 6.0F;
    /** How far a HUD card travels into its corner; smaller because it is a card, not a window. */
    public static final float HUD_RISE = 6.0F;

    /**
     * How far past each screen edge a scrim has to reach, in pixels.
     * A scrim is drawn from the frame's own corner and the frame is translated by up to RISE, so without a margin an undimmed strip shows along one edge during the entrance. Thirty-two comfortably exceeds the largest travel and costs nothing outside the screen.
     */
    public static final int SCRIM_MARGIN = 32;

    /**
     * Pushes the frame's displacement onto the game's own matrix.
     * One fenced helper instead of nine: 1.21.6 replaced the PoseStack a GUI drew on with Matrix3x2fStack, so the two eras push, translate and pop with different names.
     * Every caller must pair this with popFrame on the same frame; an unbalanced push would misplace everything drawn after the screen.
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

    /** Pops a displacement pushed by pushFrame. */
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
     * A pose translation moves what a fill or a string lands on, while the game's scissor is set in screen pixels and ignores the pose; a clipped list in a rising panel would slide out from under its clip. A stack, because the frame, a card drawn into it and a HUD pass can each add their own.
     */
    private static final float[] FRAME_OFFSETS = new float[8];
    private static int frameDepth;

    /** How far the drawing in force is displaced vertically, in pixels. */
    public static float frameOffsetY() {
        return FRAME_OFFSETS[Math.min(frameDepth, FRAME_OFFSETS.length - 1)];
    }

    /**
     * Clips to a rectangle in the coordinates the caller is drawing in, displaced like the frame.
     * Replaces graphics.enableScissor inside a displaced frame: the game's scissor takes screen pixels and knows nothing about a translated pose, so a clipped list in a moving panel would clip in the wrong place. Pair it with graphics.disableScissor().
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

    /**
     * Starts screen leaving, and answers whether it was a screen the mod could animate.
     * @param handoff whether another of this mod's pages is taking over from it -- see ScreenState#handOffOut() and Screens#defer
     */
    static boolean beginLeave(Screen screen, boolean handoff) {
        if (!tracked(screen)) {
            return false;
        }
        ScreenState state = STATES.get(screen);
        if (handoff) {
            state.handOffOut();
        } else {
            state.leave();
        }
        return true;
    }

    /** Whether the screen's exit animation has finished. */
    static boolean left(Screen screen) {
        ScreenState state = screen == null ? null : STATES.get(screen);
        return state == null || state.left();
    }

    /**
     * Whether the exit in flight is a hand-off, which the page replacing it must be told; the two halves have to be the same transition.
     */
    static boolean leavingAsHandoff(Screen screen) {
        ScreenState state = screen == null ? null : STATES.get(screen);
        return state != null && state.handoff();
    }

    /**
     * Takes over a screen that is about to be shown: a hand-off from the page leaving, a reveal of a page the player is coming back to, or a page being opened.
     * A hand-off is one of this mod's pages replacing another: the outgoing page stopped short of nothing and the incoming one starts from the same floor with the surface already up -- one cross-fade over a shared surface. See ScreenState#handOffIn().
     * A reveal is a screen that already has a state and is installed at once, such as a dialog being dismissed over the page it was drawn over. That page never left, so playing its entrance again would flash the interface open; it is settled instead.
     * An opening is a screen with no state that has never been drawn, so its first of() starts it from nothing. Nothing is created here, because a state created before a first draw is one Screens#defer would wait for an exit from that nothing would ever draw. A thrown-away and rebuilt instance is new and gets its entrance.
     */
    static void adopt(Screen screen, boolean handoff) {
        if (screen == null) {
            return;
        }
        ScreenState state = STATES.get(screen);
        if (state == null && !handoff) {
            return;
        }
        if (state == null) {
            state = new ScreenState();
            STATES.put(screen, state);
        }
        if (handoff) {
            state.handOffIn();
        } else {
            state.settleOpen();
        }
    }


    /** A colour with its own alpha multiplied by factor, for a surface fading in or out. */
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
     * This is how a control changes state over time: the hover colour is mixed into the resting colour by the hover slide. Mixing alpha too is deliberate -- a tint laid over a panel is a low-alpha colour, and interpolating only its RGB would fade it through fully opaque greys.
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
     * Small on purpose: mix is applied to whole surfaces here, and a hover that changed a panel's face by a fifth would read as a different surface. A button's two faces are already a shade apart; this is the hand that moves between them.
     */
    public static int hovered(int resting, int target, float amount) {
        return mix(resting, target, amount);
    }


    /**
     * How long one row takes to arrive, whatever its place in the queue.
     * Constant on purpose. The obvious stagger -- subtract a delay from the elapsed time and divide by the whole entrance -- gives later rows less time, so with ENTER at 200 ms and eight delays the ninth row would finish at three quarters opacity and stay there. A fixed per-row span with the delays squeezed into what is left means every row arrives fully and the last arrives when the screen does.
     */
    private static final long ROW_FADE = 120L;

    /**
     * The progress of one row of a list that is arriving, given how far the whole list has got.
     * Each row starts a little after the one above, leading the eye down the panel. Past STAGGER_LIMIT the delay stops growing, so a forty-row picker does not trickle in for the better part of a second; the rows past the limit arrive together, which nobody notices furthest down.
     * The delay is STAGGER squeezed to fit what the entrance has left after ROW_FADE, so a shorter entrance tightens the spacing rather than leaving the last rows half arrived. At the tokens above that is 10 ms a row over eight rows, everything in place at ENTER.
     * @param index the row's position in the list
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
