---
status: accepted
---

# Pure-Java Library Scope for Business & Editorial Documents

We are building `java-html-pdf` as an embeddable pure-Java library (modeled after `openhtmltopdf`'s library-first shape, with no standalone CLI product) focused on business and editorial documents: invoices, reports, dashboards, tables, photos, Flexbox, CSS Grid, and multi-page pagination with `@page` running headers/footers.

We reuse Jsoup for HTML5 parsing and Apache PDFBox for PDF serialization, while owning the CSS parser/cascade, layout engine, and pagination core in pure Java. Arbitrary web-archiving features (JavaScript execution, CSS animations, floats, `position: sticky`) are explicit non-goals so the layout and pagination core stays tractable and deterministic.
