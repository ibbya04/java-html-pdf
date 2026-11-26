package io.javahtmlpdf.verify;

import io.javahtmlpdf.HtmlToPdf;
import io.javahtmlpdf.PageSize;
import io.javahtmlpdf.RenderResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class PaginationTest {

    @Test
    void paginatesLongTableWithRepeatingTheadAndRunningPageCounters() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            rows.append("<tr><td>Line Item #").append(i)
                    .append("</td><td>2</td><td>$100.00</td></tr>\n");
        }

        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                  <style>
                    @page {
                      size: A4;
                      margin: 64px 48px 64px 48px;
                      @top-left { content: "Acme Quarterly Ledger"; font-size: 10px; color: #6b7280; }
                      @bottom-right { content: "Page " counter(page) " of " counter(pages); font-size: 10px; color: #6b7280; }
                    }
                    * { box-sizing: border-box; }
                    body { margin: 0; font-family: sans-serif; font-size: 13px; color: #1f2937; }
                    table { width: 100%%; border-collapse: collapse; }
                    th { padding: 10px; background: #f3f4f6; border-bottom: 2px solid #d1d5db; text-align: left; }
                    td { padding: 8px 10px; border-bottom: 1px solid #e5e7eb; }
                  </style>
                </head>
                <body>
                  <h1>Quarterly Ledger</h1>
                  <table>
                    <thead>
                      <tr><th>Item Description</th><th>Qty</th><th>Amount</th></tr>
                    </thead>
                    <tbody>
                      %s
                    </tbody>
                  </table>
                </body>
                </html>
                """.formatted(rows);

        RenderResult result = HtmlToPdf.builder()
                .html(html)
                .pageSize(PageSize.A4)
                .render();

        assertFalse(result.hasWarnings(), () -> "Unexpected warnings: " + result.diagnostics());

        try (PdfAssertions pdf = PdfAssertions.assertThat(result.pdfBytes())) {
            pdf.hasPageCount(2)
                    .pageContainsText(0, "Acme Quarterly Ledger")
                    .pageContainsText(0, "Item Description")
                    .pageContainsText(0, "Line Item #1")
                    .pageContainsText(0, "Page 1 of 2")
                    .pageContainsText(1, "Acme Quarterly Ledger")
                    .pageContainsText(1, "Item Description") // repeating <thead>!
                    .pageContainsText(1, "Line Item #40")
                    .pageContainsText(1, "Page 2 of 2");
        }
    }

    @Test
    void honorsExplicitBreakBeforePage() throws Exception {
        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                  <style>
                    @page { size: A4; margin: 48px; }
                    .new-page { break-before: page; }
                  </style>
                </head>
                <body>
                  <section><h1>Chapter One</h1><p>Opening content.</p></section>
                  <section class="new-page"><h1>Chapter Two</h1><p>Starts cleanly on page two.</p></section>
                </body>
                </html>
                """;

        RenderResult result = HtmlToPdf.builder().html(html).render();

        try (PdfAssertions pdf = PdfAssertions.assertThat(result.pdfBytes())) {
            pdf.hasPageCount(2)
                    .pageContainsText(0, "Chapter One")
                    .pageContainsText(1, "Chapter Two");
        }
    }

    @Test
    void splitsLongParagraphBetweenLinesWithoutDroppingText() throws Exception {
        StringBuilder para = new StringBuilder();
        for (int i = 1; i <= 140; i++) {
            para.append("Sentence ").append(i).append(" verifies clean line fragmentation across page sheets. ");
        }

        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                  <style>
                    @page { size: A4; margin: 48px; }
                    body { margin: 0; font-family: sans-serif; font-size: 14px; line-height: 1.5; }
                  </style>
                </head>
                <body>
                  <p>%s</p>
                </body>
                </html>
                """.formatted(para);

        RenderResult result = HtmlToPdf.builder().html(html).render();

        try (PdfAssertions pdf = PdfAssertions.assertThat(result.pdfBytes())) {
            pdf.hasPageCount(2)
                    .pageContainsText(0, "Sentence 1 ")
                    .pageContainsText(1, "Sentence 140 ");
        }
    }
}
