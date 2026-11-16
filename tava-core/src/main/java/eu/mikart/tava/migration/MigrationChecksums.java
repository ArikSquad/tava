package eu.mikart.tava.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Properties;

/** SHA-256 helpers for immutable migration source files and build-generated checksum manifests. @since 1.3.0 */
public final class MigrationChecksums {
    private MigrationChecksums() {}

    /**
     * Digests the exact bytes of an immutable migration source file. Include dependent code or configuration in the
     * migration source if it changes the migration's behavior.
     *
     * @param source immutable migration source file
     * @return lowercase SHA-256 digest
     * @throws IOException if the file cannot be read
     */
    public static String sha256(Path source) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            return sha256(input);
        }
    }

    /**
     * Digests bytes from the current stream position without closing the stream.
     *
     * @param source immutable migration content
     * @return lowercase SHA-256 digest
     * @throws IOException if reading fails
     */
    public static String sha256(InputStream source) throws IOException {
        Objects.requireNonNull(source, "source");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) != -1) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * Reads a digest from a properties manifest generated during the application build.
     *
     * @param manifest properties stream, owned by the caller
     * @param source source path relative to the migration source directory, with forward slashes
     * @return validated lowercase SHA-256 digest
     * @throws IOException if the manifest cannot be read
     * @throws IllegalArgumentException if the source has no valid checksum
     */
    public static String fromManifest(InputStream manifest, String source) throws IOException {
        Properties entries = new Properties();
        entries.load(Objects.requireNonNull(manifest, "manifest"));
        String digest = entries.getProperty(Objects.requireNonNull(source, "source"));
        if (digest == null || !digest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Missing or invalid migration checksum: " + source);
        return digest;
    }
}
