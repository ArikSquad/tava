package eu.mikart.tava.migration;

import eu.mikart.tava.schema.SchemaBuilder;

public abstract class Migration implements Comparable<Migration> {
	private final int version;
	private final String description;

	protected Migration(int version, String description) {
		this.version = version;
		this.description = description;
	}

	public int version() {
		return version;
	}

	public String description() {
		return description;
	}

	public abstract void up(SchemaBuilder schema);

	public void down(SchemaBuilder schema) {
	}

	@Override
	public int compareTo(Migration o) {
		return Integer.compare(this.version, o.version);
	}
}
