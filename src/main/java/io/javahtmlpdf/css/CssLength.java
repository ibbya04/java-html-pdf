package io.javahtmlpdf.css;

import java.util.Locale;
import java.util.Optional;

/**
 * Parses and resolves CSS lengths and percentages ({@code px}, {@code pt}, {@code mm},
 * {@code cm}, {@code in}, {@code em}, {@code rem}, {@code %}, or unitless {@code 0})
 * into CSS pixels (96 DPI).
 */
public record CssLength(float value, Unit unit) {

    public enum Unit {
        PX, PT, IN, CM, MM, EM, REM, PERCENT, AUTO
    }

    public static final CssLength AUTO = new CssLength(0f, Unit.AUTO);
    public static final CssLength ZERO = new CssLength(0f, Unit.PX);

    public boolean isAuto() {
        return unit == Unit.AUTO;
    }

    public boolean isPercent() {
        return unit == Unit.PERCENT;
    }

    /**
     * Resolves this length to CSS pixels (96 DPI).
     */
    public float toPx(float fontSizePx, float rootFontSizePx, float percentageBasePx) {
        return switch (unit) {
            case PX -> value;
            case PT -> value * (96f / 72f);
            case IN -> value * 96f;
            case CM -> value * (96f / 2.54f);
            case MM -> value * (96f / 25.4f);
            case EM -> value * fontSizePx;
            case REM -> value * rootFontSizePx;
            case PERCENT -> (value / 100f) * percentageBasePx;
            case AUTO -> 0f;
        };
    }

    public static Optional<CssLength> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return Optional.empty();
        }
        if ("auto".equals(s)) {
            return Optional.of(AUTO);
        }
        if ("0".equals(s)) {
            return Optional.of(ZERO);
        }
        try {
            if (s.endsWith("px")) {
                return Optional.of(new CssLength(Float.parseFloat(s.substring(0, s.length() - 2)), Unit.PX));
            }
            if (s.endsWith("pt")) {
                return Optional.of(new CssLength(Float.parseFloat(s.substring(0, s.length() - 2)), Unit.PT));
            }
            if (s.endsWith("rem")) {
                return Optional.of(new CssLength(Float.parseFloat(s.substring(0, s.length() - 3)), Unit.REM));
            }
            if (s.endsWith("em")) {
                return Optional.of(new CssLength(Float.parseFloat(s.substring(0, s.length() - 2)), Unit.EM));
            }
            if (s.endsWith("mm")) {
                return Optional.of(new CssLength(Float.parseFloat(s.substring(0, s.length() - 2)), Unit.MM));
            }
            if (s.endsWith("cm")) {
                return Optional.of(new CssLength(Float.parseFloat(s.substring(0, s.length() - 2)), Unit.CM));
            }
            if (s.endsWith("in")) {
                return Optional.of(new CssLength(Float.parseFloat(s.substring(0, s.length() - 2)), Unit.IN));
            }
            if (s.endsWith("%")) {
                return Optional.of(new CssLength(Float.parseFloat(s.substring(0, s.length() - 1)), Unit.PERCENT));
            }
            // Bare number 0.0
            float num = Float.parseFloat(s);
            if (num == 0f) {
                return Optional.of(ZERO);
            }
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
