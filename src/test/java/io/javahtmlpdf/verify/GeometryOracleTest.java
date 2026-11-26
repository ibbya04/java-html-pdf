package io.javahtmlpdf.verify;

import io.javahtmlpdf.layout.LayoutEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryOracleTest {

    @Test
    void blockFixturesPassGeometryOracle() throws Exception {
        GeometryOracle oracle = new GeometryOracle();
        GeometryOracle.SuiteReport report = oracle.runSuite(
                List.of(
                        "/fixtures/geometry/block-basic.json",
                        "/fixtures/geometry/block-margin-collapse.json"
                ),
                new LayoutEngine()
        );

        for (GeometryOracle.FixtureResult res : report.results()) {
            assertTrue(res.passed(), () -> res.name() + " failed:\n" + String.join("\n", res.mismatches()));
        }
        assertEquals(2, report.passed());
        assertEquals(100.0, report.passRatePercent(), 0.01);
    }
}
