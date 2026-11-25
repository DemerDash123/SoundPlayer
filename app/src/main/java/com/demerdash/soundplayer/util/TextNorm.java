package com.demerdash.soundplayer.util;

import java.text.Normalizer;

/**
 * Search normalization that works for Latin and Arabic text:
 * lower-cases, removes accents / harakat / tatweel and unifies common Arabic letter variants,
 * so "Beyonce" finds "Beyoncé" and "احمد" finds "أحمد".
 */
public final class TextNorm {

    private TextNorm() {
    }

    public static String normalize(String s) {
        if (s == null || s.length() == 0) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) continue;
            if (c == 'ـ') continue; // Arabic tatweel
            switch (c) {
                case 'آ': // alef with madda
                case 'أ': // alef with hamza above
                case 'إ': // alef with hamza below
                case 'ٱ': // alef wasla
                    c = 'ا';
                    break;
                case 'ة': // taa marbuta -> haa
                    c = 'ه';
                    break;
                case 'ى': // alef maqsura -> yaa
                    c = 'ي';
                    break;
                case 'ؤ': // waw with hamza
                    c = 'و';
                    break;
                case 'ئ': // yaa with hamza
                    c = 'ي';
                    break;
                default:
                    break;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** True when every whitespace separated term of the query appears in the text. */
    public static boolean matches(String normalizedText, String normalizedQuery) {
        if (normalizedQuery.length() == 0) return true;
        String[] terms = normalizedQuery.trim().split("\\s+");
        for (String t : terms) {
            if (t.length() == 0) continue;
            if (normalizedText.indexOf(t) < 0) return false;
        }
        return true;
    }

    /** First letter used for fast-scroll sections ("#" for digits/symbols). */
    public static String sectionLetter(String title) {
        if (title == null) return "#";
        String t = title.trim();
        if (t.length() == 0) return "#";
        String n = normalize(t.substring(0, Math.min(t.length(), 2)));
        if (n.length() == 0) return "#";
        char c = n.charAt(0);
        if (Character.isLetter(c)) return String.valueOf(Character.toUpperCase(c));
        return "#";
    }
}
