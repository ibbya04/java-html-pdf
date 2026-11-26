package io.javahtmlpdf.css;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java CSS3 stylesheet parser supporting selector rules, {@code @page} rules,
 * inline {@code style="..."} declaration lists, comments, {@code !important}, and shorthand expansion.
 */
public final class CssParser {

    private CssParser() {}

    public static CssStylesheet parse(String css, boolean userAgent, int startOrder) {
        if (css == null || css.isBlank()) {
            return CssStylesheet.EMPTY;
        }
        String cleaned = stripComments(css);
        List<CssRule> rules = new ArrayList<>();
        Map<String, String> pageDeclarations = new LinkedHashMap<>();

        int pos = 0;
        int len = cleaned.length();
        int order = startOrder;

        while (pos < len) {
            pos = skipWhitespace(cleaned, pos);
            if (pos >= len) break;

            if (cleaned.charAt(pos) == '@') {
                int braceOpen = cleaned.indexOf('{', pos);
                int semi = cleaned.indexOf(';', pos);
                if (semi >= 0 && (braceOpen < 0 || semi < braceOpen)) {
                    // At-rule without block (e.g., @charset, @import)
                    pos = semi + 1;
                    continue;
                }
                if (braceOpen < 0) break;
                String prelude = cleaned.substring(pos, braceOpen).trim().toLowerCase(Locale.ROOT);
                int braceClose = findMatchingBrace(cleaned, braceOpen);
                if (braceClose < 0) break;
                String block = cleaned.substring(braceOpen + 1, braceClose);
                if (prelude.startsWith("@page")) {
                    for (CssRule.Declaration decl : parseDeclarations(block)) {
                        pageDeclarations.put(decl.property(), decl.value());
                    }
                }
                pos = braceClose + 1;
                continue;
            }

            int braceOpen = cleaned.indexOf('{', pos);
            if (braceOpen < 0) break;
            int braceClose = findMatchingBrace(cleaned, braceOpen);
            if (braceClose < 0) break;

            String selectorGroup = cleaned.substring(pos, braceOpen).trim();
            String body = cleaned.substring(braceOpen + 1, braceClose);
            List<CssRule.Declaration> declarations = parseDeclarations(body);

            if (!selectorGroup.isEmpty() && !declarations.isEmpty()) {
                for (String rawSel : selectorGroup.split(",")) {
                    String sel = rawSel.trim();
                    if (!sel.isEmpty()) {
                        rules.add(new CssRule(sel, computeSpecificity(sel), order++, userAgent, declarations));
                    }
                }
            }
            pos = braceClose + 1;
        }

        return new CssStylesheet(rules, pageDeclarations);
    }

    public static List<CssRule.Declaration> parseDeclarations(String block) {
        List<CssRule.Declaration> result = new ArrayList<>();
        if (block == null || block.isBlank()) {
            return result;
        }
        String cleaned = stripComments(block);
        for (String entry : splitOutsideParens(cleaned, ';')) {
            int colon = entry.indexOf(':');
            if (colon <= 0) continue;
            String prop = entry.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String val = entry.substring(colon + 1).trim();
            if (prop.isEmpty() || val.isEmpty()) continue;

            boolean important = false;
            String lowerVal = val.toLowerCase(Locale.ROOT);
            if (lowerVal.endsWith("!important")) {
                important = true;
                val = val.substring(0, val.length() - "!important".length()).trim();
            }
            expandShorthand(prop, val, important, result);
        }
        return result;
    }

    private static void expandShorthand(String prop, String val, boolean important, List<CssRule.Declaration> out) {
        switch (prop) {
            case "margin" -> expandBoxShorthand("margin", val, important, out);
            case "padding" -> expandBoxShorthand("padding", val, important, out);
            case "border" -> expandBorderShorthand(null, val, important, out);
            case "border-top" -> expandBorderShorthand("top", val, important, out);
            case "border-right" -> expandBorderShorthand("right", val, important, out);
            case "border-bottom" -> expandBorderShorthand("bottom", val, important, out);
            case "border-left" -> expandBorderShorthand("left", val, important, out);
            case "border-width" -> expandBorderEdgeProperty("width", val, important, out);
            case "border-color" -> expandBorderEdgeProperty("color", val, important, out);
            case "gap" -> {
                List<String> parts = splitTokensRespectingParens(val);
                if (!parts.isEmpty()) {
                    out.add(new CssRule.Declaration("row-gap", parts.get(0), important));
                    out.add(new CssRule.Declaration("column-gap", parts.size() >= 2 ? parts.get(1) : parts.get(0), important));
                }
            }
            case "flex" -> expandFlexShorthand(val, important, out);
            case "background" -> {
                if (CssColor.parse(val).isPresent()) {
                    out.add(new CssRule.Declaration("background-color", val, important));
                } else {
                    // Extract a color token if mixed with other background tokens
                    for (String tok : splitTokensRespectingParens(val)) {
                        if (CssColor.parse(tok).isPresent()) {
                            out.add(new CssRule.Declaration("background-color", tok, important));
                            break;
                        }
                    }
                }
            }
            default -> out.add(new CssRule.Declaration(prop, val, important));
        }
    }

    private static void expandBoxShorthand(String prefix, String val, boolean important, List<CssRule.Declaration> out) {
        List<String> parts = splitTokensRespectingParens(val);
        if (parts.isEmpty()) return;
        String top = parts.get(0);
        String right = parts.size() >= 2 ? parts.get(1) : top;
        String bottom = parts.size() >= 3 ? parts.get(2) : top;
        String left = parts.size() >= 4 ? parts.get(3) : right;
        out.add(new CssRule.Declaration(prefix + "-top", top, important));
        out.add(new CssRule.Declaration(prefix + "-right", right, important));
        out.add(new CssRule.Declaration(prefix + "-bottom", bottom, important));
        out.add(new CssRule.Declaration(prefix + "-left", left, important));
    }

    private static void expandBorderEdgeProperty(String suffix, String val, boolean important, List<CssRule.Declaration> out) {
        List<String> parts = splitTokensRespectingParens(val);
        if (parts.isEmpty()) return;
        String top = parts.get(0);
        String right = parts.size() >= 2 ? parts.get(1) : top;
        String bottom = parts.size() >= 3 ? parts.get(2) : top;
        String left = parts.size() >= 4 ? parts.get(3) : right;
        out.add(new CssRule.Declaration("border-top-" + suffix, top, important));
        out.add(new CssRule.Declaration("border-right-" + suffix, right, important));
        out.add(new CssRule.Declaration("border-bottom-" + suffix, bottom, important));
        out.add(new CssRule.Declaration("border-left-" + suffix, left, important));
    }

    private static void expandBorderShorthand(String side, String val, boolean important, List<CssRule.Declaration> out) {
        String lower = val.trim().toLowerCase(Locale.ROOT);
        String[] sides = side == null ? new String[]{"top", "right", "bottom", "left"} : new String[]{side};
        if ("none".equals(lower) || "0".equals(lower)) {
            for (String s : sides) {
                out.add(new CssRule.Declaration("border-" + s + "-width", "0", important));
            }
            return;
        }
        String width = null;
        String color = null;
        boolean styleNone = false;
        for (String tok : splitTokensRespectingParens(val)) {
            String lt = tok.toLowerCase(Locale.ROOT);
            if ("none".equals(lt) || "hidden".equals(lt)) {
                styleNone = true;
            } else if ("solid".equals(lt) || "dashed".equals(lt) || "dotted".equals(lt) || "double".equals(lt)) {
                // recognized border style
            } else if ("thin".equals(lt)) {
                width = "1px";
            } else if ("medium".equals(lt)) {
                width = "3px";
            } else if ("thick".equals(lt)) {
                width = "5px";
            } else if (CssLength.parse(tok).isPresent()) {
                width = tok;
            } else if (CssColor.parse(tok).isPresent()) {
                color = tok;
            }
        }
        if (styleNone) {
            width = "0";
        } else if (width == null) {
            width = "1px";
        }
        for (String s : sides) {
            out.add(new CssRule.Declaration("border-" + s + "-width", width, important));
            if (color != null) {
                out.add(new CssRule.Declaration("border-" + s + "-color", color, important));
            }
        }
    }

    private static void expandFlexShorthand(String val, boolean important, List<CssRule.Declaration> out) {
        String lower = val.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(lower)) {
            out.add(new CssRule.Declaration("flex-grow", "0", important));
            out.add(new CssRule.Declaration("flex-shrink", "0", important));
            out.add(new CssRule.Declaration("flex-basis", "auto", important));
            return;
        }
        if ("auto".equals(lower)) {
            out.add(new CssRule.Declaration("flex-grow", "1", important));
            out.add(new CssRule.Declaration("flex-shrink", "1", important));
            out.add(new CssRule.Declaration("flex-basis", "auto", important));
            return;
        }
        List<String> parts = splitTokensRespectingParens(val);
        if (parts.size() == 1) {
            String tok = parts.get(0);
            if (isBareNumber(tok)) {
                out.add(new CssRule.Declaration("flex-grow", tok, important));
                out.add(new CssRule.Declaration("flex-shrink", "1", important));
                out.add(new CssRule.Declaration("flex-basis", "0px", important));
            } else {
                out.add(new CssRule.Declaration("flex-grow", "1", important));
                out.add(new CssRule.Declaration("flex-shrink", "1", important));
                out.add(new CssRule.Declaration("flex-basis", tok, important));
            }
        } else if (parts.size() == 2) {
            out.add(new CssRule.Declaration("flex-grow", parts.get(0), important));
            if (isBareNumber(parts.get(1))) {
                out.add(new CssRule.Declaration("flex-shrink", parts.get(1), important));
                out.add(new CssRule.Declaration("flex-basis", "0px", important));
            } else {
                out.add(new CssRule.Declaration("flex-shrink", "1", important));
                out.add(new CssRule.Declaration("flex-basis", parts.get(1), important));
            }
        } else if (parts.size() >= 3) {
            out.add(new CssRule.Declaration("flex-grow", parts.get(0), important));
            out.add(new CssRule.Declaration("flex-shrink", parts.get(1), important));
            out.add(new CssRule.Declaration("flex-basis", parts.get(2), important));
        }
    }

    private static boolean isBareNumber(String s) {
        try {
            Float.parseFloat(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static CssRule.Specificity computeSpecificity(String selector) {
        int ids = 0;
        int classes = 0;
        int tags = 0;
        String[] parts = selector.trim().split("\\s+|>");
        for (String part : parts) {
            if (part.isEmpty() || "*".equals(part)) continue;
            int i = 0;
            while (i < part.length()) {
                char c = part.charAt(i);
                if (c == '#') {
                    ids++;
                    i = advanceIdentifier(part, i + 1);
                } else if (c == '.' || c == ':') {
                    classes++;
                    i = advanceIdentifier(part, i + 1);
                } else if (c == '[') {
                    classes++;
                    int end = part.indexOf(']', i);
                    i = end < 0 ? part.length() : end + 1;
                } else if (Character.isLetter(c)) {
                    tags++;
                    i = advanceIdentifier(part, i);
                } else {
                    i++;
                }
            }
        }
        return new CssRule.Specificity(ids, classes, tags);
    }

    private static int advanceIdentifier(String s, int start) {
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private static String stripComments(String css) {
        StringBuilder sb = new StringBuilder(css.length());
        int i = 0;
        while (i < css.length()) {
            if (i + 1 < css.length() && css.charAt(i) == '/' && css.charAt(i + 1) == '*') {
                int end = css.indexOf("*/", i + 2);
                if (end < 0) break;
                i = end + 2;
            } else {
                sb.append(css.charAt(i++));
            }
        }
        return sb.toString();
    }

    private static int skipWhitespace(String s, int pos) {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        return pos;
    }

    private static int findMatchingBrace(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static List<String> splitOutsideParens(String s, char delimiter) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (c == delimiter && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) {
            parts.add(s.substring(start));
        }
        return parts;
    }

    private static List<String> splitTokensRespectingParens(String s) {
        List<String> tokens = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                if (start < 0) start = i;
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (Character.isWhitespace(c) && depth == 0) {
                if (start >= 0) {
                    tokens.add(s.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        if (start >= 0) {
            tokens.add(s.substring(start));
        }
        return tokens;
    }
}
