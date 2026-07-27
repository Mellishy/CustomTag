package com.mellishy.customtag.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The anti-bypass engine behind the whole blacklist: if any of these foldings regress, every
 * word filter in the plugin can be walked around with leet-speak, separators or lookalike
 * unicode - so each documented bypass class gets its own pin.
 */
class TextNormalizerTest {

    @Test
    void normalize_lowercases() {
        assertEquals("owner", TextNormalizer.normalize("OWNER"));
    }

    @Test
    void normalize_foldsLeetSpeak() {
        assertEquals("owner", TextNormalizer.normalize("0wner"));
        assertEquals("admin", TextNormalizer.normalize("4dm1n"));
        assertEquals("staff", TextNormalizer.normalize("$t4ff"));
    }

    @Test
    void normalize_stripsSeparators() {
        assertEquals("owner", TextNormalizer.normalize("o w n e r"));
        assertEquals("owner", TextNormalizer.normalize("O_w-n.e~r"));
        assertEquals("owner", TextNormalizer.normalize("[OWNER]"));
    }

    @Test
    void normalize_foldsCyrillicAndGreekLookalikes() {
        // 'о' below is CYRILLIC SMALL LETTER O, not the Latin letter
        assertEquals("owner", TextNormalizer.normalize("\u043Ewner"));
        // Greek omicron
        assertEquals("owner", TextNormalizer.normalize("\u03BFwner"));
    }

    @Test
    void normalize_removesInvisibleCharacters() {
        // zero-width space smuggled into the middle of a blocked word
        assertEquals("owner", TextNormalizer.normalize("own\u200Ber"));
    }

    @Test
    void normalize_leavesNormalForeignTextAlone() {
        // Persian text is NOT a bypass attempt and must survive normalization
        assertEquals("\u0633\u0644\u0627\u0645", TextNormalizer.normalize("\u0633\u0644\u0627\u0645"));
    }

    @Test
    void containsInvisible_detectsTheUnicodeAbuseClass() {
        assertTrue(TextNormalizer.containsInvisible("a\u200Bb"), "zero width space");
        assertTrue(TextNormalizer.containsInvisible("a\u202Eb"), "bidi override");
        assertTrue(TextNormalizer.containsInvisible("a\u00ADb"), "soft hyphen");
        assertFalse(TextNormalizer.containsInvisible("Plain Tag 123"));
        assertFalse(TextNormalizer.containsInvisible(null));
    }
}
