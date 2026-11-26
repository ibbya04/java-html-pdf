package io.javahtmlpdf.font;

import io.javahtmlpdf.PageSize;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Manages registered custom TTF/OTF fonts and PDF Standard 14 fallbacks,
 * providing exact Font Metrics (glyph widths, ascent, descent) for layout and painting.
 */
public final class FontRegistry {

    public record FontKey(String family, boolean bold, boolean italic) {
        public FontKey {
            family = normalizeFamily(family);
        }
    }

    private final Map<FontKey, byte[]> registeredFonts = new HashMap<>();

    public void register(String family, boolean bold, boolean italic, byte[] ttfBytes) {
        registeredFonts.put(new FontKey(family, bold, italic), ttfBytes);
    }

    public FontRegistry copy() {
        FontRegistry copy = new FontRegistry();
        copy.registeredFonts.putAll(this.registeredFonts);
        return copy;
    }

    public LoadedFonts bindTo(PDDocument document) {
        return new LoadedFonts(document, Map.copyOf(registeredFonts));
    }

    public static String normalizeFamily(String family) {
        if (family == null) return "sans-serif";
        String first = family.split(",")[0].trim();
        if ((first.startsWith("\"") && first.endsWith("\""))
                || (first.startsWith("'") && first.endsWith("'"))) {
            first = first.substring(1, first.length() - 1).trim();
        }
        return first.isEmpty() ? "sans-serif" : first.toLowerCase(Locale.ROOT);
    }

    /**
     * Per-document cache of loaded {@link PDFont} instances and Font Metrics helpers.
     */
    public static final class LoadedFonts {
        private final PDDocument document;
        private final Map<FontKey, byte[]> registered;
        private final Map<FontKey, PDFont> cache = new HashMap<>();

        LoadedFonts(PDDocument document, Map<FontKey, byte[]> registered) {
            this.document = document;
            this.registered = registered;
        }

        public PDFont resolve(String fontFamilyList, boolean bold, boolean italic) throws IOException {
            if (fontFamilyList != null && !fontFamilyList.isBlank()) {
                for (String raw : fontFamilyList.split(",")) {
                    String fam = normalizeFamily(raw);
                    PDFont custom = tryLoadCustom(fam, bold, italic);
                    if (custom != null) {
                        return custom;
                    }
                }
            }
            // Fallback to Standard 14 based on generic family
            String primary = normalizeFamily(fontFamilyList);
            FontKey fallbackKey = new FontKey("__std14_" + genericCategory(primary), bold, italic);
            PDFont cached = cache.get(fallbackKey);
            if (cached != null) {
                return cached;
            }
            PDFont std = new PDType1Font(standard14Name(genericCategory(primary), bold, italic));
            cache.put(fallbackKey, std);
            return std;
        }

        private PDFont tryLoadCustom(String family, boolean bold, boolean italic) throws IOException {
            FontKey exact = new FontKey(family, bold, italic);
            if (cache.containsKey(exact)) {
                return cache.get(exact);
            }
            byte[] bytes = registered.get(exact);
            if (bytes == null && bold && italic) {
                bytes = registered.get(new FontKey(family, true, false));
            }
            if (bytes == null && (bold || italic)) {
                bytes = registered.get(new FontKey(family, false, false));
            }
            if (bytes == null) {
                return null;
            }
            PDFont loaded = PDType0Font.load(document, new ByteArrayInputStream(bytes), true);
            cache.put(exact, loaded);
            return loaded;
        }

        public float measureWidthPx(PDFont font, float fontSizePx, String text) throws IOException {
            if (text == null || text.isEmpty()) {
                return 0f;
            }
            String safe = sanitizeForFont(font, text);
            float widthThousandths = font.getStringWidth(safe);
            float fontSizePt = fontSizePx * PageSize.PX_TO_PT;
            float widthPt = (widthThousandths / 1000f) * fontSizePt;
            return widthPt * PageSize.PT_TO_PX;
        }

        public float ascentPx(PDFont font, float fontSizePx) {
            float ascent = font.getFontDescriptor() != null ? font.getFontDescriptor().getAscent() : 800f;
            if (ascent <= 0f) ascent = 800f;
            return (ascent / 1000f) * fontSizePx;
        }

        public float descentPx(PDFont font, float fontSizePx) {
            float descent = font.getFontDescriptor() != null ? Math.abs(font.getFontDescriptor().getDescent()) : 200f;
            if (descent <= 0f) descent = 200f;
            return (descent / 1000f) * fontSizePx;
        }

        public String sanitizeForFont(PDFont font, String text) {
            if (text == null) return "";
            if (font instanceof PDType0Font) {
                return text;
            }
            // Standard 14 WinAnsi encoding fallback for common typography symbols
            StringBuilder sb = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '\u2014' -> sb.append('-'); // em-dash
                    case '\u2013' -> sb.append('-'); // en-dash
                    case '\u2018', '\u2019' -> sb.append('\'');
                    case '\u201C', '\u201D' -> sb.append('"');
                    case '\u2026' -> sb.append("...");
                    case '\u2192' -> sb.append("->");
                    case '\u00A0' -> sb.append(' ');
                    default -> {
                        if (c >= 32 && c <= 255) {
                            sb.append(c);
                        } else if (c == '\u2022') {
                            sb.append('\u2022'); // bullet is in WinAnsi
                        } else {
                            sb.append('?');
                        }
                    }
                }
            }
            return sb.toString();
        }

        private static String genericCategory(String family) {
            if (family.contains("mono") || family.contains("courier") || family.contains("consolas") || family.contains("code")) {
                return "monospace";
            }
            if (family.contains("serif") && !family.contains("sans-serif")) {
                return "serif";
            }
            if (family.contains("times") || family.contains("georgia")) {
                return "serif";
            }
            return "sans-serif";
        }

        private static Standard14Fonts.FontName standard14Name(String category, boolean bold, boolean italic) {
            return switch (category) {
                case "monospace" -> {
                    if (bold && italic) yield Standard14Fonts.FontName.COURIER_BOLD_OBLIQUE;
                    if (bold) yield Standard14Fonts.FontName.COURIER_BOLD;
                    if (italic) yield Standard14Fonts.FontName.COURIER_OBLIQUE;
                    yield Standard14Fonts.FontName.COURIER;
                }
                case "serif" -> {
                    if (bold && italic) yield Standard14Fonts.FontName.TIMES_BOLD_ITALIC;
                    if (bold) yield Standard14Fonts.FontName.TIMES_BOLD;
                    if (italic) yield Standard14Fonts.FontName.TIMES_ITALIC;
                    yield Standard14Fonts.FontName.TIMES_ROMAN;
                }
                default -> {
                    if (bold && italic) yield Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE;
                    if (bold) yield Standard14Fonts.FontName.HELVETICA_BOLD;
                    if (italic) yield Standard14Fonts.FontName.HELVETICA_OBLIQUE;
                    yield Standard14Fonts.FontName.HELVETICA;
                }
            };
        }
    }
}
