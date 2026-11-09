package eu.mikart.tava.schema.plan;

import org.jetbrains.annotations.NotNull;

/**
 * Controls whether planned schema changes with data-loss risks may be applied.
 *
 * @param allowLossy whether changes that may lose information may be applied
 * @param allowDestructive whether changes that may remove or replace stored data may be applied
 */
public record ApplyOptions(boolean allowLossy, boolean allowDestructive) {
    /**
     * Returns options that reject lossy and destructive changes.
     *
     * @return options with both risk allowances disabled
     */
    public static @NotNull ApplyOptions safe() {
        return new ApplyOptions(false, false);
    }
}
