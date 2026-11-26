package io.javahtmlpdf.css;

import io.javahtmlpdf.PageSize;
import io.javahtmlpdf.RenderDiagnostic;
import io.javahtmlpdf.ResourceLoader;
import io.javahtmlpdf.model.BreakProps;
import io.javahtmlpdf.model.Display;
import io.javahtmlpdf.model.Edges;
import io.javahtmlpdf.model.FlexProps;
import io.javahtmlpdf.model.GridProps;
import io.javahtmlpdf.model.LayoutBox;
import io.javahtmlpdf.model.LayoutNode;
import io.javahtmlpdf.model.LayoutStyle;
import io.javahtmlpdf.model.MarginBoxSpec;
import io.javahtmlpdf.model.TableRole;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the CSS Cascade (User-Agent + author stylesheets + inline styles + inheritance)
 * across a Jsoup DOM tree to produce a {@link LayoutNode} Styled Tree and {@code @page} settings.
 */
public final class StyleResolver {

    public record ResolvedDocument(
            LayoutNode root,
            PageSize pageSize,
            Edges pageMarginsPx,
            List<MarginBoxSpec> marginBoxes
    ) {}

    private static final Set<String> SUPPORTED_PROPERTIES = Set.of(
            "display", "width", "height", "min-width", "min-height", "max-width", "box-sizing",
            "margin-top", "margin-right", "margin-bottom", "margin-left",
            "padding-top", "padding-right", "padding-bottom", "padding-left",
            "border-top-width", "border-right-width", "border-bottom-width", "border-left-width",
            "border-top-color", "border-right-color", "border-bottom-color", "border-left-color",
            "border-radius", "background-color", "color",
            "font-family", "font-size", "font-weight", "font-style",
            "line-height", "text-align", "text-decoration", "white-space", "overflow",
            "list-style", "list-style-type", "object-fit", "letter-spacing", "text-underline-offset",
            "text-transform", "border-collapse",
            // Flexbox
            "flex-direction", "flex-wrap", "justify-content", "align-items", "align-self",
            "flex-grow", "flex-shrink", "flex-basis", "row-gap", "column-gap",
            // Grid
            "grid-template-columns", "grid-template-rows", "grid-column", "grid-row",
            // Pagination / Fragmentation
            "break-before", "break-after", "break-inside",
            "page-break-before", "page-break-after", "page-break-inside",
            "orphans", "widows"
    );

    private static final Set<String> INHERITED_PROPERTIES = Set.of(
            "color", "font-family", "font-size", "font-weight", "font-style",
            "line-height", "text-align", "text-decoration"
    );

    public ResolvedDocument resolve(
            Document doc,
            PageSize defaultPageSize,
            ResourceLoader resourceLoader,
            List<RenderDiagnostic> diagnostics
    ) {
        List<CssRule> allRules = new ArrayList<>();
        Map<String, String> pageDecls = new HashMap<>();
        Map<MarginBoxSpec.Position, Map<String, String>> rawMarginBoxes = new HashMap<>();

        CssStylesheet ua = UserAgentStylesheet.load();
        allRules.addAll(ua.rules());

        int nextOrder = ua.rules().size() + 1;

        // Collect external <link rel="stylesheet"> and inline <style> in document order
        for (Element el : doc.select("link[rel=stylesheet][href], style")) {
            if ("style".equalsIgnoreCase(el.tagName())) {
                CssStylesheet sheet = CssParser.parse(el.data(), false, nextOrder);
                allRules.addAll(sheet.rules());
                pageDecls.putAll(sheet.pageDeclarations());
                sheet.pageMarginBoxes().forEach((k, v) -> rawMarginBoxes.computeIfAbsent(k, p -> new HashMap<>()).putAll(v));
                nextOrder += sheet.rules().size() + 1;
            } else if ("link".equalsIgnoreCase(el.tagName())) {
                String href = el.attr("href");
                try {
                    Optional<byte[]> bytes = resourceLoader.load(href);
                    if (bytes.isPresent()) {
                        String cssText = new String(bytes.get(), StandardCharsets.UTF_8);
                        CssStylesheet sheet = CssParser.parse(cssText, false, nextOrder);
                        allRules.addAll(sheet.rules());
                        pageDecls.putAll(sheet.pageDeclarations());
                        sheet.pageMarginBoxes().forEach((k, v) -> rawMarginBoxes.computeIfAbsent(k, p -> new HashMap<>()).putAll(v));
                        nextOrder += sheet.rules().size() + 1;
                    } else {
                        diagnostics.add(RenderDiagnostic.warning("MISSING_STYLESHEET", "Could not load stylesheet: " + href));
                    }
                } catch (IOException e) {
                    diagnostics.add(RenderDiagnostic.warning("MISSING_STYLESHEET", "Error loading stylesheet " + href + ": " + e.getMessage()));
                }
            }
        }

        PageSize resolvedPageSize = resolvePageSize(pageDecls.get("size"), defaultPageSize);
        Edges pageMargins = resolvePageMargins(pageDecls);

        Set<String> reportedUnsupported = new HashSet<>();
        Element htmlEl = doc.selectFirst("html");
        LayoutStyle rootStyle = LayoutStyle.defaults();
        if (htmlEl != null) {
            Map<String, String> htmlProps = computeElementProperties(htmlEl, allRules, Map.of(), reportedUnsupported, diagnostics);
            rootStyle = buildLayoutStyle(htmlProps, LayoutStyle.defaults(), 16f);
        }
        float rootFontSizePx = rootStyle.fontSizePx();

        Element bodyEl = doc.body();
        LayoutNode bodyNode = bodyEl == null
                ? new LayoutNode("body", rootStyle, null, List.of())
                : buildNode(bodyEl, allRules, Map.of(), rootStyle, rootFontSizePx, null, resourceLoader, reportedUnsupported, diagnostics);

        List<MarginBoxSpec> marginBoxes = resolveMarginBoxes(rawMarginBoxes, bodyNode.style().fontFamily());
        return new ResolvedDocument(bodyNode, resolvedPageSize, pageMargins, marginBoxes);
    }

    private static List<MarginBoxSpec> resolveMarginBoxes(
            Map<MarginBoxSpec.Position, Map<String, String>> raw,
            String defaultFontFamily
    ) {
        List<MarginBoxSpec> out = new ArrayList<>();
        for (Map.Entry<MarginBoxSpec.Position, Map<String, String>> entry : raw.entrySet()) {
            Map<String, String> props = entry.getValue();
            String content = props.get("content");
            if (content == null || content.isBlank() || "none".equalsIgnoreCase(content.trim())) {
                continue;
            }
            CssColor color = CssColor.parse(props.get("color")).orElse(new CssColor(107, 114, 128, 1f));
            float fontSize = resolveLengthPx(props.get("font-size"), 11f, 16f);
            if (fontSize <= 0f) fontSize = 11f;
            String fontFamily = props.getOrDefault("font-family", defaultFontFamily);
            out.add(new MarginBoxSpec(entry.getKey(), content, color, fontSize, fontFamily));
        }
        return out;
    }

    private LayoutNode buildNode(
            Element element,
            List<CssRule> rules,
            Map<String, String> parentRawProps,
            LayoutStyle parentStyle,
            float rootFontSizePx,
            String inheritedHref,
            ResourceLoader resourceLoader,
            Set<String> reportedUnsupported,
            List<RenderDiagnostic> diagnostics
    ) {
        Map<String, String> rawProps = computeElementProperties(element, rules, parentRawProps, reportedUnsupported, diagnostics);
        LayoutStyle style = buildLayoutStyle(rawProps, parentStyle, rootFontSizePx);
        String tag = element.tagName().toLowerCase(Locale.ROOT);
        TableRole tableRole = switch (tag) {
            case "table" -> TableRole.TABLE;
            case "thead" -> TableRole.THEAD;
            case "tbody" -> TableRole.TBODY;
            case "tfoot" -> TableRole.TFOOT;
            case "tr" -> TableRole.TR;
            case "th" -> TableRole.TH;
            case "td" -> TableRole.TD;
            default -> TableRole.NONE;
        };
        if (tableRole != TableRole.NONE) {
            int colSpan = 1;
            if (element.hasAttr("colspan")) {
                colSpan = Math.max(1, (int) parseFloatOr(element.attr("colspan"), 1f));
            }
            style = style.withTableRole(tableRole, colSpan);
        }
        String id = element.id().isBlank() ? tag : element.id();

        if (style.display() == Display.NONE) {
            return new LayoutNode(id, style, null, List.of());
        }

        String activeHref = ("a".equals(tag) && element.hasAttr("href"))
                ? element.attr("href")
                : inheritedHref;

        // Replaced element: <img>
        if ("img".equals(tag)) {
            String src = element.attr("src");
            byte[] imgBytes = null;
            if (!src.isBlank()) {
                try {
                    Optional<byte[]> loaded = resourceLoader.load(src);
                    if (loaded.isPresent()) {
                        imgBytes = loaded.get();
                    } else {
                        diagnostics.add(RenderDiagnostic.warning("MISSING_IMAGE", "Could not load image: " + src));
                    }
                } catch (IOException e) {
                    diagnostics.add(RenderDiagnostic.warning("MISSING_IMAGE", "Error loading image " + src + ": " + e.getMessage()));
                }
            }
            // HTML width/height attributes as fallback if CSS didn't specify
            LayoutStyle imgStyle = applyHtmlDimensionAttributes(element, style);
            return new LayoutNode(id, imgStyle, null, imgBytes, activeHref, List.of());
        }

        // Line break: <br>
        if ("br".equals(tag)) {
            return new LayoutNode("br", style, "\n", null, activeHref, List.of());
        }

        List<LayoutNode> children = new ArrayList<>();
        List<Node> domChildren = element.childNodes();
        for (int i = 0; i < domChildren.size(); i++) {
            Node child = domChildren.get(i);
            if (child instanceof TextNode textNode) {
                String collapsed = collapseWhitespace(textNode.getWholeText());
                if (collapsed.isEmpty()) {
                    continue;
                }
                if (" ".equals(collapsed) && !isBetweenInlineSiblings(domChildren, i)) {
                    continue;
                }
                children.add(new LayoutNode("#text", style, collapsed, null, activeHref, List.of()));
            } else if (child instanceof Element childEl) {
                LayoutNode childNode = buildNode(
                        childEl, rules, rawProps, style, rootFontSizePx,
                        activeHref, resourceLoader, reportedUnsupported, diagnostics
                );
                if (childNode.style().display() != Display.NONE) {
                    children.add(childNode);
                }
            }
        }

        return new LayoutNode(id, style, null, null, activeHref, children);
    }

    private LayoutStyle applyHtmlDimensionAttributes(Element img, LayoutStyle style) {
        CssLength w = style.width();
        CssLength h = style.height();
        if (w == null && img.hasAttr("width")) {
            w = CssLength.parse(img.attr("width") + (img.attr("width").matches("\\d+") ? "px" : "")).orElse(null);
        }
        if (h == null && img.hasAttr("height")) {
            h = CssLength.parse(img.attr("height") + (img.attr("height").matches("\\d+") ? "px" : "")).orElse(null);
        }
        if (w == style.width() && h == style.height()) {
            return style;
        }
        return style.withDimensions(w, h);
    }

    private record MatchedDecl(CssRule.Declaration decl, boolean userAgent, CssRule.Specificity specificity, int order) {}

    private Map<String, String> computeElementProperties(
            Element element,
            List<CssRule> rules,
            Map<String, String> parentRawProps,
            Set<String> reportedUnsupported,
            List<RenderDiagnostic> diagnostics
    ) {
        Map<String, String> props = new HashMap<>();
        // Seed inherited properties from parent
        for (String inherited : INHERITED_PROPERTIES) {
            if (parentRawProps.containsKey(inherited)) {
                props.put(inherited, parentRawProps.get(inherited));
            }
        }

        List<MatchedDecl> matched = new ArrayList<>();
        for (CssRule rule : rules) {
            if (matchesSelector(element, rule.selector())) {
                for (CssRule.Declaration decl : rule.declarations()) {
                    matched.add(new MatchedDecl(decl, rule.userAgent(), rule.specificity(), rule.sourceOrder()));
                }
            }
        }

        // Inline style="..." attribute
        if (element.hasAttr("style")) {
            List<CssRule.Declaration> inlineDecls = CssParser.parseDeclarations(element.attr("style"));
            int inlineOrder = Integer.MAX_VALUE / 2;
            for (CssRule.Declaration decl : inlineDecls) {
                matched.add(new MatchedDecl(decl, false, CssRule.Specificity.INLINE, inlineOrder++));
            }
        }

        // CSS Cascade sort: normal before !important; UA before author (reversed for !important); specificity; order
        matched.sort(Comparator
                .comparing((MatchedDecl m) -> m.decl().important())
                .thenComparingInt(m -> m.decl().important()
                        ? (m.userAgent() ? 1 : 0)
                        : (m.userAgent() ? 0 : 1))
                .thenComparing(MatchedDecl::specificity)
                .thenComparingInt(MatchedDecl::order));

        for (MatchedDecl m : matched) {
            String prop = m.decl().property();
            if (!SUPPORTED_PROPERTIES.contains(prop)) {
                if (reportedUnsupported.add(prop)) {
                    diagnostics.add(RenderDiagnostic.warning("UNSUPPORTED_CSS", "Unsupported CSS property ignored: " + prop));
                }
                continue;
            }
            props.put(prop, m.decl().value());
        }

        return props;
    }

    private LayoutStyle buildLayoutStyle(Map<String, String> props, LayoutStyle parent, float rootFontSizePx) {
        // 1. Font size first (needed to resolve em units on all other properties)
        float fontSizePx = parent.fontSizePx();
        if (props.containsKey("font-size")) {
            Optional<CssLength> parsed = CssLength.parse(props.get("font-size"));
            if (parsed.isPresent() && !parsed.get().isAuto()) {
                fontSizePx = parsed.get().toPx(parent.fontSizePx(), rootFontSizePx, parent.fontSizePx());
            }
        }

        // 2. Display & list-item
        Display display = Display.BLOCK;
        boolean listItem = false;
        String displayRaw = props.get("display");
        if (displayRaw != null) {
            String d = displayRaw.trim().toLowerCase(Locale.ROOT);
            switch (d) {
                case "none" -> display = Display.NONE;
                case "inline", "inline-block" -> display = Display.INLINE;
                case "flex" -> display = Display.FLEX;
                case "grid" -> display = Display.GRID;
                case "table" -> display = Display.TABLE;
                case "list-item" -> {
                    display = Display.BLOCK;
                    listItem = true;
                }
                default -> display = Display.BLOCK;
            }
        }

        // 3. Dimensions
        CssLength width = parseNonAutoLength(props.get("width"));
        CssLength height = parseNonAutoLength(props.get("height"));
        CssLength minWidth = parseNonAutoLength(props.get("min-width"));
        CssLength minHeight = parseNonAutoLength(props.get("min-height"));
        CssLength maxWidth = parseNonAutoLength(props.get("max-width"));

        // 4. Margins (including margin-*: auto for centering and Flex auto-margins)
        boolean marginTopAuto = "auto".equalsIgnoreCase(trimOrEmpty(props.get("margin-top")));
        boolean marginRightAuto = "auto".equalsIgnoreCase(trimOrEmpty(props.get("margin-right")));
        boolean marginBottomAuto = "auto".equalsIgnoreCase(trimOrEmpty(props.get("margin-bottom")));
        boolean marginLeftAuto = "auto".equalsIgnoreCase(trimOrEmpty(props.get("margin-left")));
        Edges margin = new Edges(
                marginTopAuto ? 0f : resolveLengthPx(props.get("margin-top"), fontSizePx, rootFontSizePx),
                marginRightAuto ? 0f : resolveLengthPx(props.get("margin-right"), fontSizePx, rootFontSizePx),
                marginBottomAuto ? 0f : resolveLengthPx(props.get("margin-bottom"), fontSizePx, rootFontSizePx),
                marginLeftAuto ? 0f : resolveLengthPx(props.get("margin-left"), fontSizePx, rootFontSizePx)
        );

        // 5. Padding & Border
        Edges padding = new Edges(
                resolveLengthPx(props.get("padding-top"), fontSizePx, rootFontSizePx),
                resolveLengthPx(props.get("padding-right"), fontSizePx, rootFontSizePx),
                resolveLengthPx(props.get("padding-bottom"), fontSizePx, rootFontSizePx),
                resolveLengthPx(props.get("padding-left"), fontSizePx, rootFontSizePx)
        );
        Edges border = new Edges(
                resolveLengthPx(props.get("border-top-width"), fontSizePx, rootFontSizePx),
                resolveLengthPx(props.get("border-right-width"), fontSizePx, rootFontSizePx),
                resolveLengthPx(props.get("border-bottom-width"), fontSizePx, rootFontSizePx),
                resolveLengthPx(props.get("border-left-width"), fontSizePx, rootFontSizePx)
        );

        boolean borderBox = "border-box".equalsIgnoreCase(trimOrEmpty(props.get("box-sizing")));

        // 6. Colors
        CssColor color = CssColor.parse(props.get("color")).orElse(parent.color());
        CssColor bg = CssColor.parse(props.get("background-color"))
                .filter(c -> !c.isTransparent())
                .orElse(null);
        CssColor btColor = CssColor.parse(props.get("border-top-color")).orElse(color);
        CssColor brColor = CssColor.parse(props.get("border-right-color")).orElse(color);
        CssColor bbColor = CssColor.parse(props.get("border-bottom-color")).orElse(color);
        CssColor blColor = CssColor.parse(props.get("border-left-color")).orElse(color);
        float borderRadius = resolveLengthPx(props.get("border-radius"), fontSizePx, rootFontSizePx);

        // 7. Typography
        String fontFamily = props.getOrDefault("font-family", parent.fontFamily());
        boolean bold = parseBold(props.get("font-weight"), parent.bold());
        boolean italic = parseItalic(props.get("font-style"), parent.italic());
        float lineHeight = parseLineHeight(props.get("line-height"), fontSizePx, rootFontSizePx, parent.lineHeightMultiplier());
        LayoutStyle.TextAlign textAlign = parseTextAlign(props.get("text-align"), parent.textAlign());
        boolean underline = parseUnderline(props.get("text-decoration"), parent.underline());
        boolean whiteSpaceNowrap = "nowrap".equalsIgnoreCase(trimOrEmpty(props.get("white-space")));
        boolean overflowHidden = "hidden".equalsIgnoreCase(trimOrEmpty(props.get("overflow")));

        // 8. Flexbox properties
        float rowGap = resolveLengthPx(props.get("row-gap"), fontSizePx, rootFontSizePx);
        float colGap = resolveLengthPx(props.get("column-gap"), fontSizePx, rootFontSizePx);
        FlexProps flex = new FlexProps(
                parseFlexDirection(props.get("flex-direction")),
                parseFlexWrap(props.get("flex-wrap")),
                parseJustifyContent(props.get("justify-content")),
                parseAlignItems(props.get("align-items")),
                parseAlignSelf(props.get("align-self")),
                parseFloatOr(props.get("flex-grow"), 0f),
                parseFloatOr(props.get("flex-shrink"), 1f),
                parseNonAutoLength(props.get("flex-basis")),
                rowGap,
                colGap
        );

        // 9. Grid properties
        int[] colPlace = parseGridPlacement(props.get("grid-column"));
        int[] rowPlace = parseGridPlacement(props.get("grid-row"));
        GridProps grid = new GridProps(
                parseGridTracks(props.get("grid-template-columns")),
                parseGridTracks(props.get("grid-template-rows")),
                colPlace[0], colPlace[1],
                rowPlace[0], rowPlace[1],
                rowGap,
                colGap
        );

        // 10. Pagination / Break properties
        BreakProps breaks = new BreakProps(
                isPageBreak(props.get("break-before")) || isPageBreak(props.get("page-break-before")),
                isAvoidBreak(props.get("break-after")) || isAvoidBreak(props.get("page-break-after")),
                isAvoidBreak(props.get("break-inside")) || isAvoidBreak(props.get("page-break-inside")),
                Math.max(1, (int) parseFloatOr(props.get("orphans"), 2f)),
                Math.max(1, (int) parseFloatOr(props.get("widows"), 2f))
        );

        return new LayoutStyle(
                display, width, height, minWidth, minHeight, maxWidth,
                margin, marginTopAuto, marginRightAuto, marginBottomAuto, marginLeftAuto,
                padding, border, borderBox,
                bg, btColor, brColor, bbColor, blColor, borderRadius,
                color, fontFamily, fontSizePx, bold, italic, lineHeight,
                textAlign, underline, whiteSpaceNowrap, listItem, overflowHidden,
                flex, grid, TableRole.NONE, 1, breaks
        );
    }

    private static PageSize resolvePageSize(String raw, PageSize fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        boolean landscape = lower.contains("landscape");
        PageSize base = fallback;
        if (lower.contains("a4")) {
            base = PageSize.A4;
        } else if (lower.contains("letter")) {
            base = PageSize.LETTER;
        }
        return landscape ? base.landscape() : base;
    }

    private static Edges resolvePageMargins(Map<String, String> pageDecls) {
        // Default Chrome print margin is ~10mm (~37.8px) if @page margin isn't set
        float def = 0f;
        boolean hasAny = pageDecls.containsKey("margin-top")
                || pageDecls.containsKey("margin-right")
                || pageDecls.containsKey("margin-bottom")
                || pageDecls.containsKey("margin-left");
        if (!hasAny) {
            return Edges.all(def);
        }
        return new Edges(
                resolveLengthPx(pageDecls.get("margin-top"), 16f, 16f),
                resolveLengthPx(pageDecls.get("margin-right"), 16f, 16f),
                resolveLengthPx(pageDecls.get("margin-bottom"), 16f, 16f),
                resolveLengthPx(pageDecls.get("margin-left"), 16f, 16f)
        );
    }

    /**
     * Pure-Java CSS selector matcher supporting universal (*), tag, .class, #id,
     * compound selectors (e.g. {@code p.intro#main}), descendant ({@code A B}), and child ({@code A > B}).
     */
    static boolean matchesSelector(Element element, String selector) {
        String trimmed = selector.trim();
        if (trimmed.isEmpty()) return false;
        // Tokenize combinators: whitespace (descendant) and '>' (direct child)
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '>') {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                tokens.add(">");
            } else if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return matchTokensRightToLeft(element, tokens, tokens.size() - 1);
    }

    private static boolean matchTokensRightToLeft(Element el, List<String> tokens, int idx) {
        if (el == null || idx < 0) return false;
        String simple = tokens.get(idx);
        if (!matchesCompound(el, simple)) return false;
        if (idx == 0) return true;

        String prev = tokens.get(idx - 1);
        if (">".equals(prev)) {
            return matchTokensRightToLeft(el.parent(), tokens, idx - 2);
        }
        // Descendant combinator: walk ancestors
        Element ancestor = el.parent();
        while (ancestor != null) {
            if (matchTokensRightToLeft(ancestor, tokens, idx - 1)) {
                return true;
            }
            ancestor = ancestor.parent();
        }
        return false;
    }

    private static boolean matchesCompound(Element el, String compound) {
        if ("*".equals(compound)) return true;
        int i = 0;
        int len = compound.length();
        while (i < len) {
            char c = compound.charAt(i);
            if (c == '#') {
                int end = nextSelectorStop(compound, i + 1);
                String expectedId = compound.substring(i + 1, end);
                if (!expectedId.equals(el.id())) return false;
                i = end;
            } else if (c == '.') {
                int end = nextSelectorStop(compound, i + 1);
                String expectedClass = compound.substring(i + 1, end);
                if (!el.hasClass(expectedClass)) return false;
                i = end;
            } else if (c == ':') {
                // Pseudo-classes not matched statically in Phase 1
                return false;
            } else {
                int end = nextSelectorStop(compound, i);
                String expectedTag = compound.substring(i, end);
                if (!"*".equals(expectedTag) && !expectedTag.equalsIgnoreCase(el.tagName())) {
                    return false;
                }
                i = end;
            }
        }
        return true;
    }

    private static int nextSelectorStop(String s, int start) {
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '#' || c == '.' || c == ':' || c == '[') break;
            i++;
        }
        return i;
    }

    private static boolean isBetweenInlineSiblings(List<Node> siblings, int index) {
        if (index <= 0 || index >= siblings.size() - 1) {
            return false;
        }
        return isInlineDomNode(siblings.get(index - 1)) && isInlineDomNode(siblings.get(index + 1));
    }

    private static boolean isInlineDomNode(Node node) {
        if (node instanceof TextNode tn) {
            return !tn.getWholeText().isBlank();
        }
        if (node instanceof Element el) {
            String tag = el.tagName().toLowerCase(Locale.ROOT);
            return switch (tag) {
                case "span", "strong", "b", "em", "i", "code", "a", "small", "u" -> true;
                default -> false;
            };
        }
        return false;
    }

    private static String collapseWhitespace(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        boolean lastSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastSpace) {
                    sb.append(' ');
                    lastSpace = true;
                }
            } else {
                sb.append(c);
                lastSpace = false;
            }
        }
        return sb.toString();
    }

    private static CssLength parseNonAutoLength(String raw) {
        if (raw == null) return null;
        Optional<CssLength> parsed = CssLength.parse(raw);
        return (parsed.isPresent() && !parsed.get().isAuto()) ? parsed.get() : null;
    }

    private static float resolveLengthPx(String raw, float fontSizePx, float rootFontSizePx) {
        if (raw == null) return 0f;
        return CssLength.parse(raw)
                .map(len -> len.toPx(fontSizePx, rootFontSizePx, 0f))
                .orElse(0f);
    }

    private static boolean parseBold(String raw, boolean fallback) {
        if (raw == null) return fallback;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if ("bold".equals(s) || "bolder".equals(s)) return true;
        if ("normal".equals(s) || "lighter".equals(s)) return false;
        try {
            return Integer.parseInt(s) >= 600;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseItalic(String raw, boolean fallback) {
        if (raw == null) return fallback;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if ("italic".equals(s) || "oblique".equals(s)) return true;
        if ("normal".equals(s)) return false;
        return fallback;
    }

    private static float parseLineHeight(String raw, float fontSizePx, float rootFontSizePx, float fallback) {
        if (raw == null) return fallback;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if ("normal".equals(s)) return 1.2f;
        try {
            return Float.parseFloat(s); // unitless multiplier e.g. 1.5
        } catch (NumberFormatException ignored) {
            Optional<CssLength> len = CssLength.parse(s);
            if (len.isPresent() && fontSizePx > 0f) {
                return len.get().toPx(fontSizePx, rootFontSizePx, fontSizePx) / fontSizePx;
            }
            return fallback;
        }
    }

    private static LayoutStyle.TextAlign parseTextAlign(String raw, LayoutStyle.TextAlign fallback) {
        if (raw == null) return fallback;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "center" -> LayoutStyle.TextAlign.CENTER;
            case "right", "end" -> LayoutStyle.TextAlign.RIGHT;
            case "left", "start" -> LayoutStyle.TextAlign.LEFT;
            default -> fallback;
        };
    }

    private static boolean parseUnderline(String raw, boolean fallback) {
        if (raw == null) return fallback;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.contains("underline")) return true;
        if (s.contains("none")) return false;
        return fallback;
    }

    private static String trimOrEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static float parseFloatOr(String raw, float fallback) {
        if (raw == null) return fallback;
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static FlexProps.FlexDirection parseFlexDirection(String raw) {
        if (raw == null) return FlexProps.FlexDirection.ROW;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "column" -> FlexProps.FlexDirection.COLUMN;
            case "row-reverse" -> FlexProps.FlexDirection.ROW_REVERSE;
            case "column-reverse" -> FlexProps.FlexDirection.COLUMN_REVERSE;
            default -> FlexProps.FlexDirection.ROW;
        };
    }

    private static FlexProps.FlexWrap parseFlexWrap(String raw) {
        if (raw == null) return FlexProps.FlexWrap.NOWRAP;
        return "wrap".equalsIgnoreCase(raw.trim()) ? FlexProps.FlexWrap.WRAP : FlexProps.FlexWrap.NOWRAP;
    }

    private static FlexProps.JustifyContent parseJustifyContent(String raw) {
        if (raw == null) return FlexProps.JustifyContent.FLEX_START;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "flex-end", "end", "right" -> FlexProps.JustifyContent.FLEX_END;
            case "center" -> FlexProps.JustifyContent.CENTER;
            case "space-between" -> FlexProps.JustifyContent.SPACE_BETWEEN;
            case "space-around" -> FlexProps.JustifyContent.SPACE_AROUND;
            case "space-evenly" -> FlexProps.JustifyContent.SPACE_EVENLY;
            default -> FlexProps.JustifyContent.FLEX_START;
        };
    }

    private static FlexProps.AlignItems parseAlignItems(String raw) {
        if (raw == null) return FlexProps.AlignItems.STRETCH;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "flex-start", "start" -> FlexProps.AlignItems.FLEX_START;
            case "flex-end", "end" -> FlexProps.AlignItems.FLEX_END;
            case "center" -> FlexProps.AlignItems.CENTER;
            default -> FlexProps.AlignItems.STRETCH;
        };
    }

    private static FlexProps.AlignSelf parseAlignSelf(String raw) {
        if (raw == null) return FlexProps.AlignSelf.AUTO;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "stretch" -> FlexProps.AlignSelf.STRETCH;
            case "flex-start", "start", "self-start" -> FlexProps.AlignSelf.FLEX_START;
            case "flex-end", "end", "self-end" -> FlexProps.AlignSelf.FLEX_END;
            case "center" -> FlexProps.AlignSelf.CENTER;
            default -> FlexProps.AlignSelf.AUTO;
        };
    }

    public static List<GridProps.GridTrack> parseGridTracks(String raw) {
        List<GridProps.GridTrack> tracks = new ArrayList<>();
        if (raw == null || raw.isBlank() || "none".equalsIgnoreCase(raw.trim())) {
            return tracks;
        }
        List<String> tokens = splitTopLevelTokens(raw.trim());
        for (String tok : tokens) {
            String lower = tok.toLowerCase(Locale.ROOT);
            if (lower.startsWith("repeat(") && lower.endsWith(")")) {
                String inner = tok.substring(7, tok.length() - 1);
                int comma = inner.indexOf(',');
                if (comma > 0) {
                    String countStr = inner.substring(0, comma).trim();
                    String pattern = inner.substring(comma + 1).trim();
                    try {
                        int count = Integer.parseInt(countStr);
                        List<GridProps.GridTrack> sub = parseGridTracks(pattern);
                        for (int i = 0; i < count; i++) {
                            tracks.addAll(sub);
                        }
                    } catch (NumberFormatException ignored) {
                        // auto-fit / auto-fill fallback: single flexible track
                        tracks.addAll(parseGridTracks(pattern));
                    }
                }
            } else if (lower.startsWith("minmax(") && lower.endsWith(")")) {
                String inner = tok.substring(7, tok.length() - 1);
                int comma = inner.indexOf(',');
                if (comma > 0) {
                    float minPx = CssLength.parse(inner.substring(0, comma).trim())
                            .map(l -> l.toPx(16f, 16f, 0f)).orElse(0f);
                    String maxStr = inner.substring(comma + 1).trim().toLowerCase(Locale.ROOT);
                    float maxFr = maxStr.endsWith("fr")
                            ? parseFloatOr(maxStr.substring(0, maxStr.length() - 2), 1f)
                            : 1f;
                    tracks.add(GridProps.GridTrack.minmax(minPx, maxFr));
                }
            } else if (lower.endsWith("fr")) {
                float fr = parseFloatOr(lower.substring(0, lower.length() - 2), 1f);
                tracks.add(GridProps.GridTrack.fr(fr));
            } else if ("auto".equals(lower)) {
                tracks.add(GridProps.GridTrack.auto());
            } else {
                CssLength.parse(tok).ifPresent(len -> tracks.add(GridProps.GridTrack.length(len)));
            }
        }
        return tracks;
    }

    private static List<String> splitTopLevelTokens(String s) {
        List<String> out = new ArrayList<>();
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
                    out.add(s.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        if (start >= 0) out.add(s.substring(start));
        return out;
    }

    private static int[] parseGridPlacement(String raw) {
        // Returns [start (0=auto, 1-based otherwise), span (>=1)]
        if (raw == null || raw.isBlank() || "auto".equalsIgnoreCase(raw.trim())) {
            return new int[]{0, 1};
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.contains("/")) {
            String[] parts = s.split("/", 2);
            String left = parts[0].trim();
            String right = parts[1].trim();
            int start = left.startsWith("span") ? 0 : (int) parseFloatOr(left, 0f);
            int span = 1;
            if (right.startsWith("span")) {
                span = Math.max(1, (int) parseFloatOr(right.substring(4).trim(), 1f));
            } else if (start > 0) {
                int end = (int) parseFloatOr(right, start + 1);
                if (end > start) span = end - start;
            }
            return new int[]{start, span};
        }
        if (s.startsWith("span")) {
            int span = Math.max(1, (int) parseFloatOr(s.substring(4).trim(), 1f));
            return new int[]{0, span};
        }
        int start = (int) parseFloatOr(s, 0f);
        return new int[]{start, 1};
    }

    private static boolean isPageBreak(String raw) {
        if (raw == null) return false;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return "page".equals(s) || "always".equals(s);
    }

    private static boolean isAvoidBreak(String raw) {
        if (raw == null) return false;
        return raw.trim().toLowerCase(Locale.ROOT).contains("avoid");
    }
}
