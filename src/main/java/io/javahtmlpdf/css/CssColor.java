package io.javahtmlpdf.css;

import java.awt.Color;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable RGBA color parsed from CSS ({@code #rgb}, {@code #rrggbb}, {@code rgb()},
 * {@code rgba()}, {@code transparent}, and standard CSS named colors).
 */
public record CssColor(int r, int g, int b, float a) {

    public static final CssColor BLACK = new CssColor(0, 0, 0, 1f);
    public static final CssColor WHITE = new CssColor(255, 255, 255, 1f);
    public static final CssColor TRANSPARENT = new CssColor(0, 0, 0, 0f);

    private static final Map<String, CssColor> NAMED = Map.ofEntries(
            Map.entry("transparent", TRANSPARENT),
            Map.entry("black", BLACK),
            Map.entry("white", WHITE),
            Map.entry("red", new CssColor(255, 0, 0, 1f)),
            Map.entry("green", new CssColor(0, 128, 0, 1f)),
            Map.entry("blue", new CssColor(0, 0, 255, 1f)),
            Map.entry("navy", new CssColor(0, 0, 128, 1f)),
            Map.entry("teal", new CssColor(0, 128, 128, 1f)),
            Map.entry("gray", new CssColor(128, 128, 128, 1f)),
            Map.entry("grey", new CssColor(128, 128, 128, 1f)),
            Map.entry("silver", new CssColor(192, 192, 192, 1f)),
            Map.entry("lightgray", new CssColor(211, 211, 211, 1f)),
            Map.entry("lightgrey", new CssColor(211, 211, 211, 1f)),
            Map.entry("darkgray", new CssColor(169, 169, 169, 1f)),
            Map.entry("darkgrey", new CssColor(169, 169, 169, 1f)),
            Map.entry("yellow", new CssColor(255, 255, 0, 1f)),
            Map.entry("orange", new CssColor(255, 165, 0, 1f)),
            Map.entry("purple", new CssColor(128, 0, 128, 1f)),
            Map.entry("maroon", new CssColor(128, 0, 0, 1f)),
            Map.entry("olive", new CssColor(128, 128, 0, 1f)),
            Map.entry("lime", new CssColor(0, 255, 0, 1f)),
            Map.entry("aqua", new CssColor(0, 255, 255, 1f)),
            Map.entry("cyan", new CssColor(0, 255, 255, 1f)),
            Map.entry("fuchsia", new CssColor(255, 0, 255, 1f)),
            Map.entry("magenta", new CssColor(255, 0, 255, 1f)),
            Map.entry("steelblue", new CssColor(70, 130, 180, 1f)),
            Map.entry("slategray", new CssColor(112, 128, 144, 1f)),
            Map.entry("crimson", new CssColor(220, 20, 60, 1f)),
            Map.entry("coral", new CssColor(255, 127, 80, 1f)),
            Map.entry("gold", new CssColor(255, 215, 0, 1f)),
            Map.entry("indigo", new CssColor(75, 0, 130, 1f)),
            Map.entry("rebeccapurple", new CssColor(102, 51, 153, 1f))
    );

    public boolean isTransparent() {
        return a <= 0.001f;
    }

    public Color toAwtColor() {
        return new Color(r, g, b);
    }

    public static Optional<CssColor> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return Optional.empty();
        }
        CssColor named = NAMED.get(s);
        if (named != null) {
            return Optional.of(named);
        }
        if (s.startsWith("#")) {
            return parseHex(s.substring(1));
        }
        if (s.startsWith("rgb(") && s.endsWith(")")) {
            return parseRgb(s.substring(4, s.length() - 1), false);
        }
        if (s.startsWith("rgba(") && s.endsWith(")")) {
            return parseRgb(s.substring(5, s.length() - 1), true);
        }
        return Optional.empty();
    }

    private static Optional<CssColor> parseHex(String digits) {
        try {
            if (digits.length() == 3) {
                int r = Integer.parseInt("" + digits.charAt(0) + digits.charAt(0), 16);
                int g = Integer.parseInt("" + digits.charAt(1) + digits.charAt(1), 16);
                int b = Integer.parseInt("" + digits.charAt(2) + digits.charAt(2), 16);
                return Optional.of(new CssColor(r, g, b, 1f));
            }
            if (digits.length() == 6) {
                int rgb = Integer.parseInt(digits, 16);
                return Optional.of(new CssColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 1f));
            }
            if (digits.length() == 8) {
                long rgba = Long.parseLong(digits, 16);
                int r = (int) ((rgba >> 24) & 0xFF);
                int g = (int) ((rgba >> 16) & 0xFF);
                int b = (int) ((rgba >> 8) & 0xFF);
                float a = (rgba & 0xFF) / 255f;
                return Optional.of(new CssColor(r, g, b, a));
            }
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<CssColor> parseRgb(String inner, boolean hasAlpha) {
        String normalized = inner.replace("/", ",");
        String[] parts = normalized.contains(",")
                ? normalized.split(",")
                : normalized.trim().split("\\s+");
        if (parts.length < 3) {
            return Optional.empty();
        }
        try {
            int r = clamp255(parseChannel(parts[0].trim()));
            int g = clamp255(parseChannel(parts[1].trim()));
            int b = clamp255(parseChannel(parts[2].trim()));
            float a = 1f;
            if (parts.length >= 4) {
                String alphaStr = parts[3].trim();
                a = alphaStr.endsWith("%")
                        ? Float.parseFloat(alphaStr.substring(0, alphaStr.length() - 1)) / 100f
                        : Float.parseFloat(alphaStr);
                a = Math.max(0f, Math.min(1f, a));
            } else if (hasAlpha) {
                a = 1f;
            }
            return Optional.of(new CssColor(r, g, b, a));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static int parseChannel(String token) {
        if (token.endsWith("%")) {
            float pct = Float.parseFloat(token.substring(0, token.length() - 1));
            return Math.round(pct * 2.55f);
        }
        return Math.round(Float.parseFloat(token));
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
