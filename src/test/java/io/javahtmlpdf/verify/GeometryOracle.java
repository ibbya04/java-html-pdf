package io.javahtmlpdf.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javahtmlpdf.css.CssLength;
import io.javahtmlpdf.css.StyleResolver;
import io.javahtmlpdf.layout.LayoutEngine;
import io.javahtmlpdf.model.Display;
import io.javahtmlpdf.model.Edges;
import io.javahtmlpdf.model.FlexProps;
import io.javahtmlpdf.model.GridProps;
import io.javahtmlpdf.model.LayoutBox;
import io.javahtmlpdf.model.LayoutNode;
import io.javahtmlpdf.model.LayoutStyle;
import io.javahtmlpdf.model.Rect;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * TIER 1 VERIFICATION: Geometry Oracle.
 *
 * <p>Loads Chromium/Taffy-style JSON layout fixtures, runs {@link LayoutEngine},
 * and deterministically verifies every box's {@code (x, y, width, height)} within tolerance.
 */
public final class GeometryOracle {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record FixtureResult(String name, boolean passed, List<String> mismatches) {}

    public record SuiteReport(int passed, int total, List<FixtureResult> results) {
        public double passRatePercent() {
            return total == 0 ? 100.0 : (passed * 100.0) / total;
        }
    }

    public FixtureResult runFixtureResource(String resourcePath, LayoutEngine engine) throws IOException {
        try (InputStream in = GeometryOracle.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Geometry fixture not found on classpath: " + resourcePath);
            }
            JsonNode rootJson = MAPPER.readTree(in);
            return runFixture(rootJson, engine);
        }
    }

    public SuiteReport runSuite(List<String> resourcePaths, LayoutEngine engine) throws IOException {
        List<FixtureResult> results = new ArrayList<>();
        int passed = 0;
        for (String path : resourcePaths) {
            FixtureResult result = runFixtureResource(path, engine);
            results.add(result);
            if (result.passed()) {
                passed++;
            }
        }
        return new SuiteReport(passed, results.size(), results);
    }

    private FixtureResult runFixture(JsonNode fixture, LayoutEngine engine) {
        String name = fixture.path("name").asText("unnamed-fixture");
        float viewportWidth = (float) fixture.path("viewportWidth").asDouble(800.0);
        float tolerance = (float) fixture.path("tolerance").asDouble(0.5);

        JsonNode rootNodeJson = fixture.path("root");
        LayoutNode rootNode = parseNode(rootNodeJson);
        LayoutBox actualRoot = engine.layout(rootNode, viewportWidth);

        List<String> mismatches = new ArrayList<>();
        compareNode(rootNodeJson, actualRoot, tolerance, mismatches);
        return new FixtureResult(name, mismatches.isEmpty(), mismatches);
    }

    private LayoutNode parseNode(JsonNode json) {
        String id = json.path("id").asText("node");
        LayoutStyle style = parseStyle(json.path("style"));
        String text = json.has("text") && !json.get("text").isNull() ? json.get("text").asText() : null;
        List<LayoutNode> children = new ArrayList<>();
        if (json.has("children") && json.get("children").isArray()) {
            for (JsonNode child : json.get("children")) {
                children.add(parseNode(child));
            }
        }
        return new LayoutNode(id, style, text, children);
    }

    private LayoutStyle parseStyle(JsonNode styleJson) {
        LayoutStyle d = LayoutStyle.defaults();
        if (styleJson == null || styleJson.isMissingNode()) {
            return d;
        }
        Display display = styleJson.has("display")
                ? Display.valueOf(styleJson.get("display").asText().toUpperCase(Locale.ROOT))
                : d.display();
        Float width = styleJson.has("width") && !styleJson.get("width").isNull()
                ? (float) styleJson.get("width").asDouble() : null;
        Float height = styleJson.has("height") && !styleJson.get("height").isNull()
                ? (float) styleJson.get("height").asDouble() : null;
        Edges margin = parseEdges(styleJson.path("margin"));
        Edges padding = parseEdges(styleJson.path("padding"));
        Edges border = parseEdges(styleJson.path("border"));
        boolean borderBox = !styleJson.has("boxSizing")
                || "border-box".equalsIgnoreCase(styleJson.get("boxSizing").asText());
        String bg = styleJson.has("backgroundColor") ? styleJson.get("backgroundColor").asText() : null;
        String bc = styleJson.has("borderColor") ? styleJson.get("borderColor").asText() : null;
        String color = styleJson.has("color") ? styleJson.get("color").asText() : "#000000";
        float fontSize = styleJson.has("fontSize") ? (float) styleJson.get("fontSize").asDouble() : d.fontSizePx();
        LayoutStyle base = LayoutStyle.simpleBlock(display, width, height, margin, padding, border, borderBox, bg, bc, color, fontSize);

        float gap = styleJson.has("gap") ? (float) styleJson.get("gap").asDouble() : 0f;
        float rowGap = styleJson.has("rowGap") ? (float) styleJson.get("rowGap").asDouble() : gap;
        float colGap = styleJson.has("columnGap") ? (float) styleJson.get("columnGap").asDouble() : gap;

        FlexProps flex = new FlexProps(
                parseEnum(styleJson, "flexDirection", FlexProps.FlexDirection.class, FlexProps.FlexDirection.ROW),
                parseEnum(styleJson, "flexWrap", FlexProps.FlexWrap.class, FlexProps.FlexWrap.NOWRAP),
                parseEnum(styleJson, "justifyContent", FlexProps.JustifyContent.class, FlexProps.JustifyContent.FLEX_START),
                parseEnum(styleJson, "alignItems", FlexProps.AlignItems.class, FlexProps.AlignItems.STRETCH),
                parseEnum(styleJson, "alignSelf", FlexProps.AlignSelf.class, FlexProps.AlignSelf.AUTO),
                styleJson.has("flexGrow") ? (float) styleJson.get("flexGrow").asDouble() : 0f,
                styleJson.has("flexShrink") ? (float) styleJson.get("flexShrink").asDouble() : 1f,
                styleJson.has("flexBasis") ? CssLength.parse(styleJson.get("flexBasis").asText()).orElse(null) : null,
                rowGap,
                colGap
        );

        GridProps grid = new GridProps(
                styleJson.has("gridTemplateColumns")
                        ? StyleResolver.parseGridTracks(styleJson.get("gridTemplateColumns").asText())
                        : List.of(),
                styleJson.has("gridTemplateRows")
                        ? StyleResolver.parseGridTracks(styleJson.get("gridTemplateRows").asText())
                        : List.of(),
                styleJson.has("gridColStart") ? styleJson.get("gridColStart").asInt() : 0,
                styleJson.has("gridColSpan") ? styleJson.get("gridColSpan").asInt() : 1,
                styleJson.has("gridRowStart") ? styleJson.get("gridRowStart").asInt() : 0,
                styleJson.has("gridRowSpan") ? styleJson.get("gridRowSpan").asInt() : 1,
                rowGap,
                colGap
        );

        return base.withFlex(flex).withGrid(grid);
    }

    private static <E extends Enum<E>> E parseEnum(JsonNode node, String field, Class<E> type, E fallback) {
        if (!node.has(field) || node.get(field).isNull()) return fallback;
        String norm = node.get(field).asText().trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return Enum.valueOf(type, norm);
    }

    private Edges parseEdges(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Edges.ZERO;
        }
        if (node.isNumber()) {
            return Edges.all((float) node.asDouble());
        }
        return new Edges(
                (float) node.path("top").asDouble(0.0),
                (float) node.path("right").asDouble(0.0),
                (float) node.path("bottom").asDouble(0.0),
                (float) node.path("left").asDouble(0.0)
        );
    }

    private void compareNode(JsonNode expectedNodeJson, LayoutBox actual, float tolerance, List<String> mismatches) {
        String id = expectedNodeJson.path("id").asText(actual.id());
        if (expectedNodeJson.has("expected")) {
            JsonNode exp = expectedNodeJson.get("expected");
            Rect expectedRect = new Rect(
                    (float) exp.path("x").asDouble(),
                    (float) exp.path("y").asDouble(),
                    (float) exp.path("width").asDouble(),
                    (float) exp.path("height").asDouble()
            );
            Rect act = actual.borderBox();
            if (!approxEqual(expectedRect.x(), act.x(), tolerance)
                    || !approxEqual(expectedRect.y(), act.y(), tolerance)
                    || !approxEqual(expectedRect.width(), act.width(), tolerance)
                    || !approxEqual(expectedRect.height(), act.height(), tolerance)) {
                mismatches.add(String.format(
                        Locale.ROOT,
                        "Node '%s': expected (x=%.2f, y=%.2f, w=%.2f, h=%.2f) but got (x=%.2f, y=%.2f, w=%.2f, h=%.2f)",
                        id,
                        expectedRect.x(), expectedRect.y(), expectedRect.width(), expectedRect.height(),
                        act.x(), act.y(), act.width(), act.height()
                ));
            }
        }

        JsonNode expChildren = expectedNodeJson.path("children");
        if (expChildren.isArray()) {
            if (expChildren.size() != actual.children().size()) {
                mismatches.add(String.format(
                        "Node '%s': expected %d children but got %d",
                        id, expChildren.size(), actual.children().size()
                ));
                return;
            }
            for (int i = 0; i < expChildren.size(); i++) {
                compareNode(expChildren.get(i), actual.children().get(i), tolerance, mismatches);
            }
        }
    }

    private static boolean approxEqual(float a, float b, float tol) {
        return Math.abs(a - b) <= tol;
    }
}
