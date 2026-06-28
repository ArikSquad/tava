package eu.mikart.tava.schema.plan;

import org.jetbrains.annotations.NotNull;

public record ApplyOptions(boolean allowLossy, boolean allowDestructive) {
    public static @NotNull ApplyOptions safe() {
        return new ApplyOptions(false, false);
    }
}
