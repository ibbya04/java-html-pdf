package io.javahtmlpdf.verify;

import io.javahtmlpdf.layout.LayoutEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryOracleTest {

    @Test
    void blockAndFlexFixturesPassGeometryOracle() throws Exception {
        GeometryOracle oracle = new GeometryOracle();
        List<String> fixtures = List.of(
                "/fixtures/geometry/block-basic.json",
                "/fixtures/geometry/block-margin-collapse.json",
                "/fixtures/geometry/flex-row-space-between.json",
                "/fixtures/geometry/flex-grow-gap.json",
                "/fixtures/geometry/flex-shrink.json",
                "/fixtures/geometry/flex-wrap.json",
                "/fixtures/geometry/flex-column.json"
        );
        GeometryOracle.SuiteReport report = oracle.runSuite(fixtures, new LayoutEngine());

        for (GeometryOracle.FixtureResult res : report.results()) {
            assertTrue(res.passed(), () -> res.name() + " failed:\n" + String.join("\n", res.mismatches()));
        }
        assertEquals(fixtures.size(), report.passed());
        assertEquals(100.0, report.passRatePercent(), 0.01);
    }
}
