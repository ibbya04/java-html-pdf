package io.javahtmlpdf.verify;

import io.javahtmlpdf.css.CssColor;
import io.javahtmlpdf.css.CssLength;
import io.javahtmlpdf.css.StyleResolver;
import io.javahtmlpdf.layout.LayoutEngine;
import io.javahtmlpdf.model.BreakProps;
import io.javahtmlpdf.model.Display;
import io.javahtmlpdf.model.Edges;
import io.javahtmlpdf.model.FlexProps;
import io.javahtmlpdf.model.GridProps;
import io.javahtmlpdf.model.LayoutBox;
import io.javahtmlpdf.model.LayoutNode;
import io.javahtmlpdf.model.LayoutStyle;
import io.javahtmlpdf.model.Rect;
import io.javahtmlpdf.model.TableRole;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Runs DioxusLabs/taffy's Chromium-verified XML Flexbox &amp; Grid test fixtures
 * directly against {@link LayoutEngine} and prints a detailed conformance scorecard.
 */
public final class TaffyXmlRunner {

    private static final Set<String> UNSUPPORTED_ATTRS = Set.of(
            "position", "top", "right", "bottom", "left", "inset",
            "aspect-ratio", "order", "align-content", "justify-content-grid",
            "justify-items", "justify-self", "grid-auto-flow", "grid-auto-rows",
            "grid-auto-columns", "grid-template-areas", "overflow-x", "overflow-y",
            "scrollbar-width", "max-height", "text-align"
    );

    public record SuiteStats(
            String suiteName,
            int totalFiles,
            int tested,
            int passed,
            int failed,
            int skippedOutOfScope,
            Map<String, Integer> skipReasons,
            List<String> sampleFailures
    ) {
        public double passRate() {
            return tested == 0 ? 0.0 : (passed * 100.0) / tested;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: TaffyXmlRunner <path-to-taffy-repo>");
            System.exit(1);
        }
        Path taffyRoot = Path.of(args[0]);
        TaffyXmlRunner runner = new TaffyXmlRunner();

        SuiteStats flex = runner.runDirectory("Taffy Flexbox (border_box_ltr)", taffyRoot.resolve("tests/xml/flex"));
        SuiteStats grid = runner.runDirectory("Taffy Grid    (border_box_ltr)", taffyRoot.resolve("tests/xml/grid"));

        printReport(flex);
        printReport(grid);
    }

    public SuiteStats runDirectory(String suiteName, Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            throw new IOException("Taffy XML directory not found: " + dir);
        }
        List<Path> xmlFiles;
        try (Stream<Path> s = Files.list(dir)) {
            xmlFiles = s.filter(p -> p.getFileName().toString().endsWith("__border_box_ltr.xml"))
                    .sorted()
                    .toList();
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        LayoutEngine engine = new LayoutEngine();

        int tested = 0;
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        Map<String, Integer> skipReasons = new TreeMap<>();
        List<String> sampleFailures = new ArrayList<>();

        for (Path file : xmlFiles) {
            Document doc = builder.parse(file.toFile());
            Element rootTest = doc.getDocumentElement();
            String testName = rootTest.getAttribute("name");

            Element inputEl = firstChildElement(rootTest, "input");
            Element expectEl = firstChildElement(rootTest, "expectations");
            if (inputEl == null || expectEl == null) {
                skipped++;
                skipReasons.merge("malformed-xml", 1, Integer::sum);
                continue;
            }
            Element rootDiv = firstAnyChildElement(inputEl);
            Element rootExp = firstChildElement(expectEl, "node");
            if (rootDiv == null || rootExp == null) {
                skipped++;
                skipReasons.merge("empty-tree", 1, Integer::sum);
                continue;
            }

            String unsupported = findUnsupportedFeature(rootDiv, true);
            if (unsupported != null) {
                skipped++;
                skipReasons.merge(unsupported, 1, Integer::sum);
                continue;
            }

            // Determine viewport width: null means indefinite (max-content shrink-to-fit)
            Element viewportEl = firstChildElement(rootTest, "viewport");
            Float viewportW = null;
            if (viewportEl != null) {
                String vw = viewportEl.getAttribute("width");
                if (vw != null && !vw.isBlank() && !"max-content".equals(vw) && !"min-content".equals(vw)) {
                    viewportW = CssLength.parse(vw).map(l -> l.toPx(16f, 16f, 800f)).orElse(null);
                }
            }
            if (rootDiv.hasAttribute("width")) {
                viewportW = CssLength.parse(rootDiv.getAttribute("width"))
                        .map(l -> l.toPx(16f, 16f, 800f)).orElse(viewportW);
            }

            LayoutNode layoutInput = toLayoutNode(rootDiv, 0);
            LayoutBox actualRoot = engine.layout(layoutInput, viewportW);

            List<String> mismatches = new ArrayList<>();
            compareRelative(rootDiv, rootExp, actualRoot, 0f, 0f, 1.0f, mismatches);

            tested++;
            if (mismatches.isEmpty()) {
                passed++;
            } else {
                failed++;
                if (sampleFailures.size() < 12) {
                    sampleFailures.add(testName + " -> " + mismatches.getFirst());
                }
            }
        }

        return new SuiteStats(suiteName, xmlFiles.size(), tested, passed, failed, skipped, skipReasons, sampleFailures);
    }

    private static String findUnsupportedFeature(Element el, boolean isRoot) {
        if (!el.getTextContent().isBlank() && firstAnyChildElement(el) == null) {
            return "text-leaf-measure";
        }
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            String name = attrs.item(i).getNodeName().toLowerCase(Locale.ROOT);
            String val = attrs.item(i).getNodeValue().trim().toLowerCase(Locale.ROOT);
            if ("position".equals(name) && !isRoot && !"relative".equals(val) && !"static".equals(val)) {
                return "child-position:" + val;
            }
            if (UNSUPPORTED_ATTRS.contains(name) && !"position".equals(name)) {
                return "attr:" + name;
            }
            if ("direction".equals(name) && !"ltr".equals(val)) {
                return "direction:" + val;
            }
            if ("flex-wrap".equals(name) && "wrap-reverse".equals(val)) {
                return "flex-wrap:wrap-reverse";
            }
            if ("align-items".equals(name) && "baseline".equals(val)) {
                return "align-items:baseline";
            }
            if ("grid-template-columns".equals(name) || "grid-template-rows".equals(name)) {
                if (val.contains("auto-fill") || val.contains("auto-fit") || val.contains("fit-content")
                        || val.contains("min-content") || val.contains("max-content") || val.contains("[")) {
                    return "grid-advanced-track";
                }
            }
            if (("margin-left".equals(name) || "margin-right".equals(name)
                    || "margin-top".equals(name) || "margin-bottom".equals(name))
                    && (val.endsWith("%") || val.startsWith("-"))) {
                return "margin-percent-or-negative";
            }
            if (val.startsWith("calc(")) {
                return "calc()";
            }
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element childEl) {
                String res = findUnsupportedFeature(childEl, false);
                if (res != null) return res;
            }
        }
        return null;
    }

    private static LayoutNode toLayoutNode(Element el, int idx) {
        String id = el.hasAttribute("id") ? el.getAttribute("id") : (el.getTagName() + "#" + idx);

        // In Taffy's XML schema, Style::DEFAULT has display = flex unless overridden
        Display display = switch (attr(el, "display", "flex")) {
            case "block" -> Display.BLOCK;
            case "grid" -> Display.GRID;
            case "none" -> Display.NONE;
            case "inline" -> Display.INLINE;
            default -> Display.FLEX;
        };

        CssLength width = parseNonAuto(el.getAttribute("width"));
        CssLength height = parseNonAuto(el.getAttribute("height"));
        CssLength minWidth = parseNonAuto(el.getAttribute("min-width"));
        CssLength minHeight = parseNonAuto(el.getAttribute("min-height"));
        CssLength maxWidth = parseNonAuto(el.getAttribute("max-width"));

        boolean marginLeftAuto = "auto".equalsIgnoreCase(el.getAttribute("margin-left"));
        boolean marginRightAuto = "auto".equalsIgnoreCase(el.getAttribute("margin-right"));
        Edges margin = new Edges(
                pxOrZero(el.getAttribute("margin-top")),
                marginRightAuto ? 0f : pxOrZero(el.getAttribute("margin-right")),
                pxOrZero(el.getAttribute("margin-bottom")),
                marginLeftAuto ? 0f : pxOrZero(el.getAttribute("margin-left"))
        );
        Edges padding = new Edges(
                pxOrZero(el.getAttribute("padding-top")),
                pxOrZero(el.getAttribute("padding-right")),
                pxOrZero(el.getAttribute("padding-bottom")),
                pxOrZero(el.getAttribute("padding-left"))
        );
        Edges border = new Edges(
                pxOrZero(el.getAttribute("border-top")),
                pxOrZero(el.getAttribute("border-right")),
                pxOrZero(el.getAttribute("border-bottom")),
                pxOrZero(el.getAttribute("border-left"))
        );

        boolean borderBox = !"content-box".equalsIgnoreCase(el.getAttribute("box-sizing"));

        float gap = pxOrZero(el.getAttribute("gap"));
        float rowGap = el.hasAttribute("row-gap") ? pxOrZero(el.getAttribute("row-gap")) : gap;
        float colGap = el.hasAttribute("column-gap") ? pxOrZero(el.getAttribute("column-gap")) : gap;

        FlexProps flex = new FlexProps(
                parseFlexDir(attr(el, "flex-direction", "row")),
                "wrap".equalsIgnoreCase(el.getAttribute("flex-wrap")) ? FlexProps.FlexWrap.WRAP : FlexProps.FlexWrap.NOWRAP,
                parseJustify(attr(el, "justify-content", "flex-start")),
                parseAlignItems(attr(el, "align-items", "stretch")),
                parseAlignSelf(attr(el, "align-self", "auto")),
                floatOr(el.getAttribute("flex-grow"), 0f),
                floatOr(el.getAttribute("flex-shrink"), 1f),
                parseNonAuto(el.getAttribute("flex-basis")),
                rowGap,
                colGap
        );

        int[] colPlace = parseGridSpan(el.getAttribute("grid-column-start"), el.getAttribute("grid-column-end"));
        int[] rowPlace = parseGridSpan(el.getAttribute("grid-row-start"), el.getAttribute("grid-row-end"));
        GridProps grid = new GridProps(
                StyleResolver.parseGridTracks(el.getAttribute("grid-template-columns")),
                StyleResolver.parseGridTracks(el.getAttribute("grid-template-rows")),
                colPlace[0], colPlace[1],
                rowPlace[0], rowPlace[1],
                rowGap,
                colGap
        );

        LayoutStyle style = new LayoutStyle(
                display, width, height, minWidth, minHeight, maxWidth,
                margin, marginLeftAuto, marginRightAuto,
                padding, border, borderBox,
                null, CssColor.BLACK, CssColor.BLACK, CssColor.BLACK, CssColor.BLACK, 0f,
                CssColor.BLACK, "sans-serif", 16f, false, false, 1.2f,
                LayoutStyle.TextAlign.LEFT, false, false, false, false,
                flex, grid, TableRole.NONE, 1, BreakProps.DEFAULT
        );

        List<LayoutNode> children = new ArrayList<>();
        NodeList nodes = el.getChildNodes();
        int cIdx = 0;
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element childEl) {
                children.add(toLayoutNode(childEl, cIdx++));
            }
        }
        return new LayoutNode(id, style, null, null, null, children);
    }

    private static void compareRelative(
            Element inputEl,
            Element expNode,
            LayoutBox actual,
            float parentAbsX,
            float parentAbsY,
            float tol,
            List<String> mismatches
    ) {
        float expX = floatOr(expNode.getAttribute("x"), 0f);
        float expY = floatOr(expNode.getAttribute("y"), 0f);
        float expW = floatOr(expNode.getAttribute("width"), 0f);
        float expH = floatOr(expNode.getAttribute("height"), 0f);

        Rect r = actual.borderBox();
        float actRelX = r.x() - parentAbsX;
        float actRelY = r.y() - parentAbsY;

        if (Math.abs(expX - actRelX) > tol
                || Math.abs(expY - actRelY) > tol
                || Math.abs(expW - r.width()) > tol
                || Math.abs(expH - r.height()) > tol) {
            mismatches.add(String.format(Locale.ROOT,
                    "%s expected(%.1f,%.1f,%.1f,%.1f) got(%.1f,%.1f,%.1f,%.1f)",
                    actual.id(), expX, expY, expW, expH, actRelX, actRelY, r.width(), r.height()));
            return;
        }

        List<Element> inputChildren = anyChildElements(inputEl);
        List<Element> expChildren = childElements(expNode, "node");
        List<Element> visibleInputChildren = new ArrayList<>();
        List<Element> visibleExpChildren = new ArrayList<>();
        for (int i = 0; i < Math.min(inputChildren.size(), expChildren.size()); i++) {
            if (!"none".equalsIgnoreCase(inputChildren.get(i).getAttribute("display"))) {
                visibleInputChildren.add(inputChildren.get(i));
                visibleExpChildren.add(expChildren.get(i));
            }
        }
        if (visibleExpChildren.size() != actual.children().size()) {
            mismatches.add(actual.id() + " child count expected=" + visibleExpChildren.size() + " got=" + actual.children().size());
            return;
        }
        for (int i = 0; i < visibleExpChildren.size(); i++) {
            compareRelative(visibleInputChildren.get(i), visibleExpChildren.get(i), actual.children().get(i), r.x(), r.y(), tol, mismatches);
        }
    }

    private static List<Element> anyChildElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element e) out.add(e);
        }
        return out;
    }

    private static void printReport(SuiteStats stats) {
        System.out.println("==========================================================");
        System.out.printf("%s%n", stats.suiteName());
        System.out.printf("  Total border_box_ltr fixtures : %d%n", stats.totalFiles());
        System.out.printf("  In-scope tested               : %d%n", stats.tested());
        System.out.printf("  PASSED                        : %d (%.1f%%)%n", stats.passed(), stats.passRate());
        System.out.printf("  FAILED                        : %d%n", stats.failed());
        System.out.printf("  Skipped (out-of-scope features): %d%n", stats.skippedOutOfScope());
        System.out.println("  Top skip reasons:");
        stats.skipReasons().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(6)
                .forEach(e -> System.out.printf("    - %-28s : %d%n", e.getKey(), e.getValue()));
        if (!stats.sampleFailures().isEmpty()) {
            System.out.println("  Sample failures (first 8):");
            stats.sampleFailures().stream().limit(8).forEach(f -> System.out.println("    * " + f));
        }
    }

    private static int[] parseGridSpan(String startAttr, String endAttr) {
        int start = 0;
        int span = 1;
        if (startAttr != null && !startAttr.isBlank() && !"auto".equalsIgnoreCase(startAttr)) {
            if (startAttr.startsWith("span ")) {
                span = Math.max(1, (int) floatOr(startAttr.substring(5), 1f));
            } else {
                start = Math.max(0, (int) floatOr(startAttr, 0f));
            }
        }
        if (endAttr != null && !endAttr.isBlank() && !"auto".equalsIgnoreCase(endAttr)) {
            if (endAttr.startsWith("span ")) {
                span = Math.max(1, (int) floatOr(endAttr.substring(5), 1f));
            } else if (start > 0) {
                int end = (int) floatOr(endAttr, start + 1);
                if (end > start) span = end - start;
            }
        }
        return new int[]{start, span};
    }

    private static String attr(Element el, String name, String def) {
        String v = el.getAttribute(name);
        return (v == null || v.isBlank()) ? def : v.trim().toLowerCase(Locale.ROOT);
    }

    private static CssLength parseNonAuto(String v) {
        if (v == null || v.isBlank() || "auto".equalsIgnoreCase(v.trim())) return null;
        return CssLength.parse(v).filter(l -> !l.isAuto()).orElse(null);
    }

    private static float pxOrZero(String v) {
        if (v == null || v.isBlank()) return 0f;
        return CssLength.parse(v).map(l -> l.toPx(16f, 16f, 0f)).orElse(0f);
    }

    private static float floatOr(String v, float def) {
        if (v == null || v.isBlank()) return def;
        try { return Float.parseFloat(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static FlexProps.FlexDirection parseFlexDir(String v) {
        return switch (v) {
            case "column" -> FlexProps.FlexDirection.COLUMN;
            case "row-reverse" -> FlexProps.FlexDirection.ROW_REVERSE;
            case "column-reverse" -> FlexProps.FlexDirection.COLUMN_REVERSE;
            default -> FlexProps.FlexDirection.ROW;
        };
    }

    private static FlexProps.JustifyContent parseJustify(String v) {
        return switch (v) {
            case "flex-end", "end" -> FlexProps.JustifyContent.FLEX_END;
            case "center" -> FlexProps.JustifyContent.CENTER;
            case "space-between" -> FlexProps.JustifyContent.SPACE_BETWEEN;
            case "space-around" -> FlexProps.JustifyContent.SPACE_AROUND;
            case "space-evenly" -> FlexProps.JustifyContent.SPACE_EVENLY;
            default -> FlexProps.JustifyContent.FLEX_START;
        };
    }

    private static FlexProps.AlignItems parseAlignItems(String v) {
        return switch (v) {
            case "flex-start", "start" -> FlexProps.AlignItems.FLEX_START;
            case "flex-end", "end" -> FlexProps.AlignItems.FLEX_END;
            case "center" -> FlexProps.AlignItems.CENTER;
            default -> FlexProps.AlignItems.STRETCH;
        };
    }

    private static FlexProps.AlignSelf parseAlignSelf(String v) {
        return switch (v) {
            case "stretch" -> FlexProps.AlignSelf.STRETCH;
            case "flex-start", "start", "self-start" -> FlexProps.AlignSelf.FLEX_START;
            case "flex-end", "end", "self-end" -> FlexProps.AlignSelf.FLEX_END;
            case "center" -> FlexProps.AlignSelf.CENTER;
            default -> FlexProps.AlignSelf.AUTO;
        };
    }

    private static Element firstChildElement(Element parent, String tag) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Element e && tag.equals(e.getTagName())) return e;
        }
        return null;
    }

    private static Element firstAnyChildElement(Element parent) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element e) return e;
        }
        return null;
    }

    private static List<Element> childElements(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Element e && tag.equals(e.getTagName())) out.add(e);
        }
        return out;
    }
}
