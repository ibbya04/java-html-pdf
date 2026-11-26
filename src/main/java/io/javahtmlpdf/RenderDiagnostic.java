package io.javahtmlpdf;

/**
 * A structured, non-fatal warning or informational notice recorded during rendering
 * when a Source Document contains unsupported CSS properties, malformed values, or missing assets.
 */
public record RenderDiagnostic(Severity severity, String code, String message) {

    public enum Severity {
        INFO,
        WARNING
    }

    public static RenderDiagnostic warning(String code, String message) {
        return new RenderDiagnostic(Severity.WARNING, code, message);
    }

    public static RenderDiagnostic info(String code, String message) {
        return new RenderDiagnostic(Severity.INFO, code, message);
    }
}
