package eu.mikart.tava.schema.plan;

public record SchemaChange(String description, ChangeRisk risk, Object nativeChange) {
}
