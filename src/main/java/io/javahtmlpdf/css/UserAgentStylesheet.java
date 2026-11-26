package io.javahtmlpdf.css;

/**
 * Built-in User-Agent Stylesheet providing standard HTML5 defaults before author CSS runs.
 */
public final class UserAgentStylesheet {

    private UserAgentStylesheet() {}

    private static final String DEFAULT_CSS = """
            head, title, meta, link, style, script { display: none; }
            html, body, div, header, footer, main, section, article, aside, nav,
            figure, figcaption, p, blockquote, ul, ol, li, h1, h2, h3, h4, h5, h6, hr {
                display: block;
            }
            span, strong, b, em, i, code, a, small, u {
                display: inline;
            }
            img {
                display: inline;
            }
            body {
                margin: 8px;
                font-size: 16px;
                line-height: 1.2;
                color: #000000;
                font-family: sans-serif;
            }
            h1 { font-size: 2em; font-weight: bold; margin: 0.67em 0; break-after: avoid; }
            h2 { font-size: 1.5em; font-weight: bold; margin: 0.83em 0; break-after: avoid; }
            h3 { font-size: 1.17em; font-weight: bold; margin: 1em 0; break-after: avoid; }
            h4 { font-size: 1em; font-weight: bold; margin: 1.33em 0; break-after: avoid; }
            p { margin: 1em 0; }
            blockquote { margin: 1em 40px; }
            ul, ol { margin: 1em 0; padding-left: 40px; }
            li { display: list-item; }
            strong, b { font-weight: bold; }
            em, i { font-style: italic; }
            code { font-family: monospace; }
            a { color: #0000ee; text-decoration: underline; }
            table { display: table; border-collapse: collapse; }
            th { font-weight: bold; text-align: left; }
            """;

    public static CssStylesheet load() {
        return CssParser.parse(DEFAULT_CSS, true, 0);
    }
}
