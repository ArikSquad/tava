package eu.mikart.tava.data;

import org.jetbrains.annotations.Nullable;

/** Portable details about a rejected conditional or constraint write. */
public record ConflictDetail(Kind kind, @Nullable String constraint, @Nullable String code,
                             @Nullable String message) {
    public enum Kind { UNIQUE, FOREIGN_KEY, CHECK, CONDITIONAL, UNKNOWN }
}
