package bili.dongsz.howtogo.client;

import bili.dongsz.howtogo.HowToGo;
import com.ibm.icu.text.Transliterator;
import java.text.Normalizer;

final class Pinyin {
    private static final Transliterator HAN_LATIN = create();

    private Pinyin() { }

    /**
     * Text with Han characters replaced by pinyin syllables; unchanged when it has none or ICU is
     * unavailable. Pure ASCII short-circuits because ICU also rewrites punctuation, corrupting
     * already-valid server addresses and dimension ids.
     */
    static String latin(String text) {
        if (text==null||text.isEmpty()||HAN_LATIN==null) {
            return text;
        }
        // no Han possible here, leave it alone
        if (text.chars().allMatch(c->c < 0x80)) {
            return text;
        }

        try {
            String transliterated = HAN_LATIN.transliterate(text);
            // NFD splits a tone-marked vowel into letter plus combining macron; removing marks leaves
            // the pinyin a file name wants.
            return Normalizer.normalize(transliterated, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "");
        } catch (RuntimeException | LinkageError failed) {
            // A transliterator that throws must not cost the caller its directory; the old naming
            // rule is still usable.
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