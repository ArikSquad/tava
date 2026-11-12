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
     * Allows the named risks; unsupported changes can never be allowed.
     *
     * @param risks risks to allow
     * @return explicit risk allowances
     */
    public static @NotNull ApplyOptions allowing(ChangeRisk... risks) {
        boolean lossy = false;
        boolean destructive = false;
        for (ChangeRisk risk : risks) {
            switch (risk) {
                case SAFE -> {}
                case LOSSY -> lossy = true;
                case DESTRUCTIVE -> destructive = true;
                case UNSUPPORTED -> throw new IllegalArgumentException("Unsupported changes cannot be allowed");
            }
        }
        return new ApplyOptions(lossy, destructive);
    }

    /**
     * Returns options that reject lossy and destructive changes.
     *
     * @return options with both risk allowances disabled
     */
    public static @NotNull ApplyOptions safe() {
        return new ApplyOptions(false, false);
    }
}
