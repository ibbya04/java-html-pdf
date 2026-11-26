package io.javahtmlpdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

/**
 * Pluggable boundary responsible for resolving external assets (images, stylesheets, fonts)
 * referenced by a Source Document.
 *
 * <p>By default, only inline {@code data:} URIs and local files within a configured base directory
 * are permitted. Remote {@code http://} and {@code https://} requests are blocked unless a caller
 * explicitly provides a custom ResourceLoader (see ADR-0002).
 */
@FunctionalInterface
public interface ResourceLoader {

    Optional<byte[]> load(String uri) throws IOException;

    /**
     * Safe default loader: resolves {@code data:} URIs and files strictly inside {@code baseDir}
     * (preventing directory traversal and blocking network access).
     */
    static ResourceLoader safeLocal(Path baseDir) {
        Path normalizedBase = baseDir == null ? null : baseDir.toAbsolutePath().normalize();
        return uri -> {
            if (uri == null || uri.isBlank()) {
                return Optional.empty();
            }
            if (uri.startsWith("data:")) {
                return decodeDataUri(uri);
            }
            if (uri.startsWith("http://") || uri.startsWith("https://")) {
                return Optional.empty();
            }
            if (normalizedBase == null) {
                return Optional.empty();
            }
            Path resolved = normalizedBase.resolve(uri).normalize();
            if (!resolved.startsWith(normalizedBase) || !Files.isRegularFile(resolved)) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(resolved));
        };
    }

    private static Optional<byte[]> decodeDataUri(String uri) {
        int comma = uri.indexOf(',');
        if (comma < 0) {
            return Optional.empty();
        }
        String header = uri.substring(5, comma);
        String payload = uri.substring(comma + 1);
        if (header.endsWith(";base64")) {
            return Optional.of(Base64.getDecoder().decode(payload));
        }
        return Optional.of(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
