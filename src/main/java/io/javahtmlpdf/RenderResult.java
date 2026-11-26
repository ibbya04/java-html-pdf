package io.javahtmlpdf;

import java.util.List;

/**
 * Result of rendering a Source Document into a PDF, carrying both the generated PDF bytes
 * and any non-fatal {@link RenderDiagnostic} messages collected along the way.
 */
public record RenderResult(byte[] pdfBytes, List<RenderDiagnostic> diagnostics) {

    public RenderResult {
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean hasWarnings() {
        return diagnostics.stream().anyMatch(d -> d.severity() == RenderDiagnostic.Severity.WARNING);
    }
}
