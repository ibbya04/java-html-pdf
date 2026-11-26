package io.javahtmlpdf.verify;

import io.javahtmlpdf.HtmlToPdf;
import io.javahtmlpdf.RenderDiagnostic;
import io.javahtmlpdf.RenderResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test-only scratchpad runner for manually trying any HTML file against our engine
 * and (when Chrome is installed) seeing a side-by-side visual diff.
 *
 * <p>Usage:
 * <pre>
 * mvn -q test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
 *   -Dexec.classpathScope=test \
 *   -Dexec.mainClass=io.javahtmlpdf.verify.RenderScratchpad \
 *   -Dexec.args="path/to/page.html"
 * </pre>
 */
public final class RenderScratchpad {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: RenderScratchpad <input.html> [output.pdf]");
            System.exit(1);
        }

        Path inputHtml = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(inputHtml)) {
            System.err.println("Input HTML file not found: " + inputHtml);
            System.exit(1);
        }

        String baseName = stripExtension(inputHtml.getFileName().toString());
        Path outputPdf = args.length >= 2
                ? Path.of(args[1]).toAbsolutePath().normalize()
                : Path.of("target", "preview", baseName + ".pdf").toAbsolutePath();

        Files.createDirectories(outputPdf.getParent());

        String html = Files.readString(inputHtml, StandardCharsets.UTF_8);
        RenderResult result = HtmlToPdf.builder()
                .html(html)
                .baseDirectory(inputHtml.getParent())
                .render();

        Files.write(outputPdf, result.pdfBytes());
        System.out.println("Rendered PDF : " + outputPdf);

        if (result.diagnostics().isEmpty()) {
            System.out.println("Diagnostics  : none");
        } else {
            System.out.println("Diagnostics  :");
            for (RenderDiagnostic d : result.diagnostics()) {
                System.out.printf("  [%s] %s: %s%n", d.severity(), d.code(), d.message());
            }
        }

        VisualDiffBenchmark benchmark = new VisualDiffBenchmark();
        if (benchmark.findChromeBinary().isPresent()) {
            byte[] chromePdf = benchmark.renderWithChrome(inputHtml);
            VisualDiffBenchmark.BenchmarkReport report =
                    benchmark.comparePdfs(baseName, chromePdf, result.pdfBytes());
            System.out.printf("Chrome Diff  : %.2f%% similarity (Chrome %d pg vs Java %d pg)%n",
                    report.averageSimilarityPercent(), report.referencePages(), report.actualPages());
            for (VisualDiffBenchmark.PageDiff pd : report.pages()) {
                System.out.println("Diff PNG     : " + pd.diffImage().toAbsolutePath());
            }
        }
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
