package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import com.ibm.icu.text.Transliterator;

import java.text.Normalizer;

/**
 * Chinese as Latin letters, for the parts of a file name a player has to be able to tell apart.
 *
 * <h2>Why the world directory needs this</h2>
 * The per-world data directory is named after the world, and a file name can only hold a few
 * characters. Replacing everything else with {@code _} is safe but useless for a Chinese world name:
 * {@code 新世界} and {@code 旧世界} both become the same run of underscores, and a player opening
 * {@code config/howtogo} cannot tell which directory is which. Transliterating the name to pinyin
 * first -- {@code xin_shi_jie} -- keeps both the file-name safety and the ability to recognise a
 * world at a glance, and it is what a Chinese player would type to look for it anyway.
 *
 * <h2>Where the pinyin comes from</h2>
 * ICU4J's {@code Han-Latin} transliterator, which Minecraft itself already ships as a library (it
 * uses ICU for text handling), so this adds no dependency to the mod and no table to keep up to
 * date. ICU writes the syllables with tone marks and spaces; the marks are stripped and the spaces
 * are left for the caller's file-name sanitiser to turn into separators, which gives
 * {@code xin_shi_jie}.
 *
 * <p>ICU is looked up once and allowed to be missing: if a launcher or a future Minecraft does not
 * have it, {@link #latin} hands the text back unchanged and the caller falls back to the old
 * underscore rule, so a world directory is never lost over a transliteration.
 */
final class Pinyin {

    /** The transliterator, or null when ICU is not on this classpath after all. */
    private static final Transliterator HAN_LATIN = create();

    private Pinyin() {
    }

    /**
     * The text with its Han characters replaced by pinyin syllables, or the text unchanged when it
     * has none or when ICU is unavailable.
     *
     * <p>Everything that is not a Han character passes through as it was, so an ASCII world name -- or
     * the {@code sp_} prefix in front of a Chinese one -- is untouched.
     */
    static String latin(String text) {
        if (text == null || text.isEmpty() || HAN_LATIN == null) {
            return text;
        }
        try {
            String transliterated = HAN_LATIN.transliterate(text);
            // NFD splits "xīn" into "xi" + combining macron; removing the combining marks leaves the
            // letter, which is exactly the pinyin a file name wants.
            return Normalizer.normalize(transliterated, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "");
        } catch (RuntimeException | LinkageError failed) {
            // A transliterator that throws must not cost the caller its directory: the old naming
            // rule is still usable, and it is what every existing world already has.
            return text;
        }
    }

    private static Transliterator create() {
        try {
            return Transliterator.getInstance("Han-Latin");
        } catch (RuntimeException | LinkageError missing) {
            HowToGo.LOGGER.warn("[HowToGo] pinyin transliteration is unavailable; world directories "
                    + "will keep using underscores for Chinese names: {}", missing.toString());
            return null;
        }
    }
}
