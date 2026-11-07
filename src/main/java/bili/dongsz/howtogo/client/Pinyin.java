package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import com.ibm.icu.text.Transliterator;

import java.text.Normalizer;

// 此文件用于把中文字符改为拼音存储

final class Pinyin {

    /** The transliterator, or null when ICU is not on this classpath after all. */
    private static final Transliterator HAN_LATIN = create();

    private Pinyin() {
    }

    /**
     * The text with its Han characters replaced by pinyin syllables, or the text unchanged when it
     * has none or when ICU is unavailable.
     *
     * <p>Text that is entirely ASCII is returned before the transliterator is asked. ICU does not only
     * transliterate Han characters -- it rewrites punctuation too, turning {@code :} into
     * {@code ": "} -- so a server address or a dimension id would come back with a doubled separator.
     * Those are not names to transliterate, and a name that was already usable must come back
     * byte-identical or every path built from it changes for no reason.
     */
    static String latin(String text) {
        if (text == null || text.isEmpty() || HAN_LATIN == null) {
            return text;
        }
        if (text.chars().allMatch(character -> character < 0x80)) {
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
