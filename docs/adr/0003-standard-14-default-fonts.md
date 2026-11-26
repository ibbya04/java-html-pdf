---
status: accepted
---

# PDF Standard 14 Default Fonts with Explicit TTF/OTF Registration

When a caller renders HTML without registering custom font files, the library falls back to PDF's built-in Standard 14 fonts (Helvetica, Times, Courier) rather than bundling multi-megabyte TTF font binaries in the library JAR. Callers needing custom typography or full Unicode coverage register TTF/OTF fonts explicitly through the library API.

## Consequences

The distributed library JAR remains lightweight with zero bundled font licensing overhead. For our **Visual Diff Benchmark**, test fixtures bundle a fixed open-source TTF font under test resources so Chromium and our engine use identical font metrics.
