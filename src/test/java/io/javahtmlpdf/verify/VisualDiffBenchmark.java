package io.javahtmlpdf.verify;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * TIER 3 VERIFICATION: Visual Diff Benchmark against Headless Chrome.
 *
 * <p>Renders an HTML fixture using headless Chrome ({@code --print-to-pdf}), rasterizes both
 * the Chromium reference PDF and our Java engine's PDF via PDFBox {@link PDFRenderer}, computes
 * a deterministic pixel-similarity percentage, and writes a 3-panel diff PNG to
 * {@code target/visual-diffs/} for inspection.
 */
public final class VisualDiffBenchmark {

    private static final float DEFAULT_DPI = 150f;
    private static final int DEFAULT_CHANNEL_TOLERANCE = 8;

    public record PageDiff(int pageNumber, double similarityPercent, long differingPixels, long totalPixels, Path diffImage) {}

    public record BenchmarkReport(int referencePages, int actualPages, double averageSimilarityPercent, List<PageDiff> pages) {}

    public Optional<Path> findChromeBinary() {
        String envPath = System.getenv("CHROME_PATH");
        if (envPath != null && !envPath.isBlank() && Files.isExecutable(Path.of(envPath))) {
            return Optional.of(Path.of(envPath));
        }
        List<String> candidates = List.of(
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Chromium.app/Contents/MacOS/Chromium",
                "/usr/bin/google-chrome",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser"
        );
        for (String candidate : candidates) {
            Path p = Path.of(candidate);
            if (Files.isExecutable(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    public byte[] renderWithChrome(Path htmlFile) throws IOException, InterruptedException {
        Path chrome = findChromeBinary()
                .orElseThrow(() -> new IllegalStateException("Chrome/Chromium binary not found; set CHROME_PATH"));
        Path tempPdf = Files.createTempFile("chrome-ref-", ".pdf");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    chrome.toString(),
                    "--headless=new",
                    "--disable-gpu",
                    "--no-sandbox",
                    "--no-pdf-header-footer",
                    "--print-to-pdf=" + tempPdf.toAbsolutePath(),
                    htmlFile.toAbsolutePath().toUri().toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                throw new IOException("Headless Chrome failed (exit=" + (finished ? process.exitValue() : "timeout") + "): " + output);
            }
            return Files.readAllBytes(tempPdf);
        } finally {
            Files.deleteIfExists(tempPdf);
        }
    }

    public BenchmarkReport comparePdfs(String benchmarkName, byte[] referencePdf, byte[] actualPdf) throws IOException {
        Path diffDir = Path.of("target", "visual-diffs");
        Files.createDirectories(diffDir);

        try (PDDocument refDoc = Loader.loadPDF(referencePdf);
             PDDocument actDoc = Loader.loadPDF(actualPdf)) {

            PDFRenderer refRenderer = new PDFRenderer(refDoc);
            PDFRenderer actRenderer = new PDFRenderer(actDoc);

            int refPages = refDoc.getNumberOfPages();
            int actPages = actDoc.getNumberOfPages();
            int comparedPages = Math.min(refPages, actPages);

            List<PageDiff> pageDiffs = new ArrayList<>();
            double similaritySum = 0.0;

            for (int i = 0; i < comparedPages; i++) {
                BufferedImage refImg = refRenderer.renderImageWithDPI(i, DEFAULT_DPI);
                BufferedImage actImg = actRenderer.renderImageWithDPI(i, DEFAULT_DPI);
                Path diffOut = diffDir.resolve(benchmarkName + "-page-" + (i + 1) + ".png");
                PageDiff diff = compareImages(i + 1, refImg, actImg, DEFAULT_CHANNEL_TOLERANCE, diffOut);
                pageDiffs.add(diff);
                similaritySum += diff.similarityPercent();
            }

            int maxPages = Math.max(refPages, actPages);
            double avgSimilarity = maxPages == 0 ? 100.0 : similaritySum / maxPages;
            return new BenchmarkReport(refPages, actPages, avgSimilarity, pageDiffs);
        }
    }

    private PageDiff compareImages(
            int pageNumber,
            BufferedImage refImg,
            BufferedImage actImg,
            int channelTolerance,
            Path diffOut
    ) throws IOException {
        int width = Math.max(refImg.getWidth(), actImg.getWidth());
        int height = Math.max(refImg.getHeight(), actImg.getHeight());
        long totalPixels = (long) width * height;
        long diffPixels = 0;

        BufferedImage diffOnly = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int refRgb = (x < refImg.getWidth() && y < refImg.getHeight()) ? refImg.getRGB(x, y) : 0xFFFFFF;
                int actRgb = (x < actImg.getWidth() && y < actImg.getHeight()) ? actImg.getRGB(x, y) : 0xFFFFFF;

                int dr = Math.abs(((refRgb >> 16) & 0xFF) - ((actRgb >> 16) & 0xFF));
                int dg = Math.abs(((refRgb >> 8) & 0xFF) - ((actRgb >> 8) & 0xFF));
                int db = Math.abs((refRgb & 0xFF) - (actRgb & 0xFF));

                if (dr > channelTolerance || dg > channelTolerance || db > channelTolerance) {
                    diffPixels++;
                    diffOnly.setRGB(x, y, 0xE11D48); // red highlight
                } else {
                    // Dim matching pixels for clear visual contrast
                    int gray = (((refRgb >> 16) & 0xFF) + ((refRgb >> 8) & 0xFF) + (refRgb & 0xFF)) / 3;
                    int faded = 200 + (gray * 55 / 255);
                    diffOnly.setRGB(x, y, (faded << 16) | (faded << 8) | faded);
                }
            }
        }

        // Write 3-panel composite: [Reference | Actual | Diff]
        BufferedImage composite = new BufferedImage(width * 3, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = composite.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width * 3, height);
        g.drawImage(refImg, 0, 0, null);
        g.drawImage(actImg, width, 0, null);
        g.drawImage(diffOnly, width * 2, 0, null);
        g.dispose();
        ImageIO.write(composite, "png", diffOut.toFile());

        double similarity = totalPixels == 0 ? 100.0 : ((totalPixels - diffPixels) * 100.0) / totalPixels;
        return new PageDiff(pageNumber, similarity, diffPixels, totalPixels, diffOut);
    }
}
