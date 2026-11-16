package eu.mikart.tava.transfer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

final class TransferCursors {
    private final Set<String> seen = new HashSet<>();
    private final int limit;
    private final String initial;

    TransferCursors(int limit, String initial) {
        this.limit = limit;
        this.initial = initial == null ? null : digest(initial);
    }

    void add(String cursor) {
        if (cursor == null) return;
        String digest = digest(cursor);
        if (digest.equals(initial) || seen.contains(digest))
            throw new IllegalStateException("Source adapter repeated a cursor");
        if (seen.size() >= limit)
            throw new IllegalStateException(
                    "Transfer cursor tracking limit reached; increase maxTrackedCursors or resume the transfer");
        seen.add(digest);
    }

    private static String digest(String cursor) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(cursor.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
