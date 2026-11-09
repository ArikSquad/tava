package eu.mikart.tava.migration;

import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * Opaque owner token for a held migration lock.
 *
 * @param owner token that identifies the lock owner
 * @since 1.3.0
 */
public record MigrationLockInfo(@NotNull String owner) {
    /**
     * Creates an owner token for a migration lock.
     *
     * @param owner token that identifies the lock owner
     * @throws IllegalArgumentException if the owner token is blank
     * @throws NullPointerException if {@code owner} is {@code null}
     * @since 1.3.0
     */
    public MigrationLockInfo {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) throw new IllegalArgumentException("owner is required");
    }
}
