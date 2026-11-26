package io.javahtmlpdf.model;

import io.javahtmlpdf.css.CssColor;

/**
 * Specification for a CSS {@code @page} Margin Box (e.g. {@code @bottom-right { content: "Page " counter(page) " of " counter(pages); }}).
 */
public record MarginBoxSpec(
        Position position,
        String contentExpression,
        CssColor color,
        float fontSizePx,
        String fontFamily
) {

    public enum Position {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

    /**
     * Evaluates CSS {@code content} tokens such as quoted strings, {@code counter(page)},
     * and {@code counter(pages)} for a specific page sheet.
     */
    public String evaluateText(int pageNumber, int totalPages) {
        if (contentExpression == null || contentExpression.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int len = contentExpression.length();
        while (i < len) {
            char c = contentExpression.charAt(i);
            if (c == '"' || c == '\'') {
                int end = contentExpression.indexOf(c, i + 1);
                if (end < 0) {
                    out.append(contentExpression.substring(i + 1));
                    break;
                }
                out.append(contentExpression, i + 1, end);
                i = end + 1;
            } else if (contentExpression.regionMatches(true, i, "counter(pages)", 0, 14)) {
                out.append(totalPages);
                i += 14;
            } else if (contentExpression.regionMatches(true, i, "counter(page)", 0, 13)) {
                out.append(pageNumber);
                i += 13;
            } else {
                i++;
            }
        }
        return out.toString();
    }
}
