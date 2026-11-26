package io.javahtmlpdf.css;

import java.util.List;

/**
 * A parsed CSS rule binding a single selector string to a list of declarations.
 */
public record CssRule(
        String selector,
        Specificity specificity,
        int sourceOrder,
        boolean userAgent,
        List<Declaration> declarations
) {

    public record Declaration(String property, String value, boolean important) {}

    public record Specificity(int ids, int classes, int tags) implements Comparable<Specificity> {
        public static final Specificity INLINE = new Specificity(1000, 0, 0);

        @Override
        public int compareTo(Specificity other) {
            int c = Integer.compare(this.ids, other.ids);
            if (c != 0) return c;
            c = Integer.compare(this.classes, other.classes);
            if (c != 0) return c;
            return Integer.compare(this.tags, other.tags);
        }
    }
}
