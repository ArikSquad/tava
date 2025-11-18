package eu.mikart.tava;

import java.util.LinkedHashSet;
import java.util.Set;

public final class ColumnDefinition {
	private final String name;
	private final String type;
	private final boolean nullable;
	private final boolean primary;
	private final boolean autoIncrement;
	private final boolean unique;
	private final Set<String> extras;

	public ColumnDefinition(String name, String type, boolean nullable, boolean primary, boolean autoIncrement, boolean unique, Set<String> extras) {
		this.name = name;
		this.type = type;
		this.nullable = nullable;
		this.primary = primary;
		this.autoIncrement = autoIncrement;
		this.unique = unique;
		this.extras = extras == null ? new LinkedHashSet<>() : extras;
	}

	public String name() {
		return name;
	}

	public String type() {
		return type;
	}

	public boolean nullable() {
		return nullable;
	}

	public boolean primary() {
		return primary;
	}

	public boolean autoIncrement() {
		return autoIncrement;
	}

	public boolean unique() {
		return unique;
	}

	public Set<String> extras() {
		return extras;
	}
}
