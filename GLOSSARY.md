# Java HTML-to-PDF Engine

A pure-Java library that compiles HTML5 and CSS3 documents into fixed-page PDF files, tailored for business and editorial documents such as invoices, reports, and dashboards.

## Language

### Document & Styling

**Source Document**:
The input HTML5 markup and associated CSS3 stylesheets provided by a caller for rendering.
_Avoid_: Input page, webpage, template

**Styled Tree**:
A tree of visible document nodes where every node has a single resolved map of computed CSS properties after applying selector matching, specificity, and inheritance.
_Avoid_: Style tree, CSSOM, decorated DOM

**User-Agent Stylesheet**:
The built-in baseline CSS rules (such as heading font sizes, paragraph margins, and bold `<strong>` weights) applied to every **Source Document** before author stylesheets.
_Avoid_: Default CSS, browser defaults

**Resource Loader**:
A pluggable boundary responsible for resolving external assets (images, stylesheets, font files) referenced by a **Source Document** without making unapproved network calls.
_Avoid_: Asset fetcher, HTTP client, file resolver

**Render Diagnostic**:
A structured, non-fatal warning recorded during rendering when a **Source Document** contains unsupported CSS properties, malformed values, or missing assets.
_Avoid_: Log message, error, exception

### Layout & Pagination

**Layout Box**:
A rectangular region produced by layout with exact coordinates and dimensions, composed of content, padding, border, and margin areas.
_Avoid_: Element rect, visual node, frame

**Formatting Context**:
The layout algorithm—such as Normal Flow, Flexbox, CSS Grid, or Table—chosen by a parent **Layout Box** to size and position its direct children.
_Avoid_: Layout mode, container type

**Line Box**:
A horizontal strip inside an inline **Formatting Context** that holds one wrapped line of **Inline Runs**.
_Avoid_: Text line, row

**Inline Run**:
A contiguous span of text sharing a single font, size, color, and decoration inside a **Line Box** (for example, one bold word or one hyperlink segment).
_Avoid_: Text span, text fragment, chunk

**Font Metrics**:
The exact glyph widths, ascent, descent, and line-gap measurements read from a loaded font file to drive line breaking and baseline alignment.
_Avoid_: Character width, text size

**Page Sheet**:
A single fixed-size physical output page (such as A4 or US Letter) containing a fragment of the laid-out **Layout Box** tree and its surrounding **Margin Boxes**.
_Avoid_: Canvas, viewport, PDF page

**Margin Box**:
A dedicated region around the edges of a **Page Sheet** (defined by CSS `@page` rules) used for running headers, footers, and page counters such as "Page 2 of 5".
_Avoid_: Header bar, footer bar, page chrome

### Verification

**Geometry Oracle**:
An automated test suite that compares computed **Layout Box** coordinates `(x, y, width, height)` directly against Chromium-verified reference fixtures.
_Avoid_: Layout unit test, coordinate test

**Visual Diff Benchmark**:
An end-to-end comparison that renders a **Source Document** through both this library and headless Chromium, rasterizes every **Page Sheet**, and scores pixel-level similarity.
_Avoid_: Screenshot test, golden test
