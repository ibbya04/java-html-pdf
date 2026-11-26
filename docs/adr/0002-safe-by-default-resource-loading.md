---
status: accepted
---

# Safe-by-Default Resource Loading

Unlike a web browser, the library does not automatically fetch `http://` or `https://` URLs referenced by `<img>`, `<link>`, or `@font-face`. By default, only inline `data:` URIs and local files resolved within a caller-supplied base URI are permitted; remote or custom asset fetching requires registering an explicit **Resource Loader**.

## Consequences

Server-side callers rendering untrusted or user-influenced HTML are protected from SSRF and unexpected network latency out of the box, at the cost of one extra configuration step for callers that legitimately need remote assets.
