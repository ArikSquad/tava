package eu.mikart.tava.schema.plan;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Describes one planned schema change and its application risk.
 *
 * @param description human-readable description of the change
 * @param risk classification used to decide whether the change may be applied
 * @param nativeChange adapter-specific representation of the change, if available
 */
public record SchemaChange(
        @NotNull String description,
        @NotNull ChangeRisk risk,
        @Nullable Object nativeChange) {}
