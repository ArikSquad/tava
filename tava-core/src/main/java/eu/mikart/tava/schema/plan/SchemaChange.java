package eu.mikart.tava.schema.plan;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record SchemaChange(@NotNull String description, @NotNull ChangeRisk risk, @Nullable Object nativeChange) {
}
