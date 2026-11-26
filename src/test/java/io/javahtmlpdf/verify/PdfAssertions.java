package io.javahtmlpdf.verify;

import io.javahtmlpdf.PageSize;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TIER 2 VERIFICATION: Structural PDF Assertions.
 *
 * <p>Reloads generated PDF bytes with Apache PDFBox to deterministically assert page counts,
 * physical MediaBox dimensions, extracted text order, and link annotations.
 */
public final class PdfAssertions implements AutoCloseable {

    private final PDDocument document;
    private final String extractedText;

    private PdfAssertions(PDDocument document) throws IOException {
        this.document = document;
        this.extractedText = new PDFTextStripper().getText(document);
    }

    public static PdfAssertions assertThat(byte[] pdfBytes) throws IOException {
        return new PdfAssertions(Loader.loadPDF(pdfBytes));
    }

    public PdfAssertions hasPageCount(int expectedPages) {
        assertEquals(expectedPages, document.getNumberOfPages(), "Unexpected PDF page count");
        return this;
    }

    public PdfAssertions hasPageSize(int zeroBasedPageIndex, PageSize expected, float tolerancePt) {
        PDPage page = document.getPage(zeroBasedPageIndex);
        PDRectangle box = page.getMediaBox();
        assertEquals(expected.widthPt(), box.getWidth(), tolerancePt, "Unexpected PDF page width (pt)");
        assertEquals(expected.heightPt(), box.getHeight(), tolerancePt, "Unexpected PDF page height (pt)");
        return this;
    }

    public PdfAssertions containsText(String expectedSubstring) {
        assertTrue(
                extractedText.contains(expectedSubstring),
                () -> "Expected PDF text to contain '" + expectedSubstring + "' but got:\n" + extractedText
        );
        return this;
    }

    public PdfAssertions containsTextInOrder(String first, String second) {
        int idx1 = extractedText.indexOf(first);
        int idx2 = extractedText.indexOf(second);
        assertTrue(idx1 >= 0, () -> "Missing expected text: '" + first + "'");
        assertTrue(idx2 >= 0, () -> "Missing expected text: '" + second + "'");
        assertTrue(idx1 < idx2, () -> "Expected '" + first + "' before '" + second + "' in:\n" + extractedText);
        return this;
    }

    public PdfAssertions hasAnnotationCount(int zeroBasedPageIndex, int expectedCount) throws IOException {
        int actual = document.getPage(zeroBasedPageIndex).getAnnotations().size();
        assertEquals(expectedCount, actual, "Unexpected PDF annotation count on page " + zeroBasedPageIndex);
        return this;
    }

    public String extractedText() {
        return extractedText;
    }

    @Override
    public void close() throws IOException {
        document.close();
    }
}
