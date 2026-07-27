package com.mellishy.customtag.validation;

import java.util.Map;

/**
 * The anti-bypass engine behind the blacklist: folds a tag down to a
 * canonical lowercase form so {@code O_w-n3r}, {@code Оwner} (Cyrillic О), {@code o w n e r}
 * and {@code 0wner} all normalize to {@code owner} and hit the same blacklist entry.
 *
 * Pure static functions - trivially unit-testable, safe from any thread.
 */
public final class TextNormalizer {

    private TextNormalizer() {}

    /** Leet-speak digits/symbols folded to the letters players use them for. */
    private static final Map<Character, Character> LEET = Map.ofEntries(
            Map.entry('0', 'o'), Map.entry('1', 'i'), Map.entry('3', 'e'), Map.entry('4', 'a'),
            Map.entry('5', 's'), Map.entry('7', 't'), Map.entry('8', 'b'), Map.entry('$', 's'),
            Map.entry('@', 'a'), Map.entry('!', 'i'), Map.entry('|', 'l'), Map.entry('+', 't')
    );

    /**
     * Common Cyrillic/Greek lookalikes folded to their Latin twins - the classic "ΟWNER with a
     * Greek Omicron" bypass. Deliberately only characters that are visually near-identical to a
     * Latin letter; normal non-Latin text (Persian, Arabic, Japanese, ...) is left untouched.
     */
    private static final Map<Character, Character> CONFUSABLES = Map.ofEntries(
            // Cyrillic
            Map.entry('а', 'a'), Map.entry('е', 'e'), Map.entry('о', 'o'), Map.entry('р', 'p'),
            Map.entry('с', 'c'), Map.entry('х', 'x'), Map.entry('у', 'y'), Map.entry('к', 'k'),
            Map.entry('м', 'm'), Map.entry('т', 't'), Map.entry('в', 'b'), Map.entry('н', 'h'),
            Map.entry('і', 'i'), Map.entry('ѕ', 's'), Map.entry('ј', 'j'), Map.entry('ԁ', 'd'),
            // Greek
            Map.entry('α', 'a'), Map.entry('ο', 'o'), Map.entry('ν', 'v'), Map.entry('ε', 'e'),
            Map.entry('ι', 'i'), Map.entry('κ', 'k'), Map.entry('ρ', 'p'), Map.entry('τ', 't'),
            Map.entry('υ', 'u'), Map.entry('χ', 'x')
    );

    /**
     * Canonical matching form: lowercase, confusables folded, leet folded, and every separator
     * (spaces, underscores, dots, dashes, brackets, invisible characters, combining marks)
     * removed entirely.
     */
    public static String normalize(String input) {
        if (input == null || input.isEmpty()) return "";
        String lower = input.toLowerCase(java.util.Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            Character folded = CONFUSABLES.get(c);
            if (folded != null) c = folded;
            Character leet = LEET.get(c);
            if (leet != null) c = leet;
            if (isSeparator(c) || isInvisible(c)) continue;
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isSeparator(char c) {
        return c == ' ' || c == '_' || c == '-' || c == '.' || c == ',' || c == '\'' || c == '"'
                || c == '[' || c == ']' || c == '(' || c == ')' || c == '{' || c == '}'
                || c == '<' || c == '>' || c == '/' || c == '\\' || c == '*' || c == '~' || c == '`';
    }

    /**
     * Zero-width / invisible / direction-control characters - the classic unicode-abuse class
     * (zero width space/joiner, BOM, bidi overrides, soft hyphen, ...).
     */
    public static boolean isInvisible(char c) {
        return c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\u200E' || c == '\u200F'
                || c == '\u2060' || c == '\uFEFF' || c == '\u00AD'
                || (c >= '\u202A' && c <= '\u202E')  // bidi embedding/override controls
                || (c >= '\u2066' && c <= '\u2069')  // bidi isolate controls
                || Character.getType(c) == Character.FORMAT
                || (Character.isISOControl(c));
    }

    /** True when the string contains ANY invisible/control character (before normalization). */
    public static boolean containsInvisible(String input) {
        if (input == null) return false;
        for (int i = 0; i < input.length(); i++) {
            if (isInvisible(input.charAt(i))) return true;
        }
        return false;
    }
}
