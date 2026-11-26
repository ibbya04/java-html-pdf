package io.javahtmlpdf.verify;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs bundled Chromium-verified XML fixtures from DioxusLabs/taffy on every {@code mvn test}.
 */
class TaffyConformanceTest {

    @Test
    void bundledTaffyFlexFixturesPass100Percent() throws Exception {
        Path flexDir = Path.of(getClass().getResource("/fixtures/taffy/flex").toURI());
        TaffyXmlRunner.SuiteStats stats = new TaffyXmlRunner().runDirectory("Bundled Taffy Flex", flexDir);
        assertEquals(5, stats.tested());
        assertTrue(stats.sampleFailures().isEmpty(), () -> "Taffy Flex failures:\n" + String.join("\n", stats.sampleFailures()));
        assertEquals(5, stats.passed());
    }

    @Test
    void bundledTaffyGridFixturesPass100Percent() throws Exception {
        Path gridDir = Path.of(getClass().getResource("/fixtures/taffy/grid").toURI());
        TaffyXmlRunner.SuiteStats stats = new TaffyXmlRunner().runDirectory("Bundled Taffy Grid", gridDir);
        assertEquals(5, stats.tested());
        assertTrue(stats.sampleFailures().isEmpty(), () -> "Taffy Grid failures:\n" + String.join("\n", stats.sampleFailures()));
        assertEquals(5, stats.passed());
    }
}
