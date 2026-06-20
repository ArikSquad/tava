package eu.mikart.tava;

import java.util.Set;

public record ColumnDefinition(
		String name,
		String type,
		boolean nullable,
		boolean primary,
		boolean autoIncrement,
		boolean unique,
		Set<String> extras
) {
	public ColumnDefinition {
		extras = extras == null ? Set.of() : Set.copyOf(extras);
	}
}
