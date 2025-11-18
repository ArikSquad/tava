package eu.mikart.tava;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

public final class TableBuilder {
	private final Dialect dialect;
	private final String tableName;
	private final Set<ColumnDefinition> columns = new LinkedHashSet<>();

	public TableBuilder(Dialect dialect, String tableName) {
		this.dialect = dialect;
		this.tableName = tableName;
	}

	public ColumnBuilder uuid(String name) {
		return new ColumnBuilder(this, name, dialect.typeUuid());
	}

	public ColumnBuilder string(String name) {
		return new ColumnBuilder(this, name, dialect.typeString(0));
	}

	public ColumnBuilder string(String name, int length) {
		return new ColumnBuilder(this, name, dialect.typeString(length));
	}

	public ColumnBuilder text(String name) {
		return new ColumnBuilder(this, name, dialect.typeText());
	}

	public ColumnBuilder integer(String name) {
		return new ColumnBuilder(this, name, dialect.typeInt());
	}

	public ColumnBuilder bigInt(String name) {
		return new ColumnBuilder(this, name, dialect.typeBigInt());
	}

	public ColumnBuilder bool(String name) {
		return new ColumnBuilder(this, name, dialect.typeBoolean());
	}

	public ColumnBuilder json(String name) {
		return new ColumnBuilder(this, name, dialect.typeJson());
	}

	public ColumnBuilder timestamp(String name) {
		return new ColumnBuilder(this, name, dialect.typeTimestamp());
	}

	public ColumnBuilder instant(String name) {
		return timestamp(name);
	}

	void add(ColumnDefinition def) {
		columns.add(def);
	}

	public Set<ColumnDefinition> columns() {
		return columns;
	}

	String tableName() {
		return tableName;
	}

	Dialect dialect() {
		return dialect;
	}

	public void build(Consumer<TableBuilder> consumer) {
		consumer.accept(this);
	}

	public static final class ColumnBuilder {
		private final TableBuilder table;
		private final String name;
		private final String type;
		private final Set<String> extras = new LinkedHashSet<>();
		private boolean nullable;
		private boolean primary;
		private boolean autoIncrement;
		private boolean unique;

		public ColumnBuilder(TableBuilder table, String name, String type) {
			this.table = table;
			this.name = name;
			this.type = type;
		}

		public ColumnBuilder nullValue() {
			this.nullable = true;
			return this;
		}

		public ColumnBuilder notNull() {
			this.nullable = false;
			return this;
		}

		public ColumnBuilder nullAllowed(boolean allowed) {
			this.nullable = allowed;
			return this;
		}

		public ColumnBuilder primary() {
			this.primary = true;
			return this;
		}

		public ColumnBuilder autoIncrement() {
			this.autoIncrement = true;
			return this;
		}

		public ColumnBuilder unique() {
			this.unique = true;
			return this;
		}

		public ColumnBuilder extra(String sqlFragment) {
			this.extras.add(sqlFragment);
			return this;
		}

		public ColumnBuilder defaultNow() {
			this.extras.add("DEFAULT CURRENT_TIMESTAMP");
			return this;
		}

		public TableBuilder end() {
			table.add(new ColumnDefinition(name, type, nullable, primary, autoIncrement, unique, extras));
			return table;
		}

		public Set<String> extras() {
			return extras;
		}
	}
}
