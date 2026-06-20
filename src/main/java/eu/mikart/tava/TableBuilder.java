package eu.mikart.tava;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class TableBuilder {
	private final Dialect dialect;
	private final String tableName;
	private final List<ColumnBuilder> columns = new ArrayList<>();

	public TableBuilder(Dialect dialect, String tableName) {
		this.dialect = dialect;
		this.tableName = tableName;
	}

	public ColumnBuilder uuid(String name) {
		return column(name, dialect.typeUuid());
	}

	public ColumnBuilder string(String name) {
		return column(name, dialect.typeString(0));
	}

	public ColumnBuilder string(String name, int length) {
		return column(name, dialect.typeString(length));
	}

	public ColumnBuilder text(String name) {
		return column(name, dialect.typeText());
	}

	public ColumnBuilder integer(String name) {
		return column(name, dialect.typeInt());
	}

	public ColumnBuilder bigInt(String name) {
		return column(name, dialect.typeBigInt());
	}

	public ColumnBuilder bool(String name) {
		return column(name, dialect.typeBoolean());
	}

	public ColumnBuilder json(String name) {
		return column(name, dialect.typeJson());
	}

	public ColumnBuilder timestamp(String name) {
		return column(name, dialect.typeTimestamp());
	}

	public ColumnBuilder instant(String name) {
		return timestamp(name);
	}

	public ColumnBuilder decimal(String name, int precision, int scale) {
		return column(name, "DECIMAL(" + precision + ", " + scale + ")");
	}

	public ColumnBuilder binary(String name) {
		return column(name, dialect.typeBinary());
	}

	private ColumnBuilder column(String name, String type) {
		var column = new ColumnBuilder(this, name, type);
		columns.add(column);
		return column;
	}

	public List<ColumnDefinition> columns() {
		return columns.stream().map(ColumnBuilder::definition).toList();
	}

	String tableName() {
		return tableName;
	}

	Dialect dialect() {
		return dialect;
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

		public ColumnBuilder nullable() {
			this.nullable = true;
			return this;
		}

		/** @deprecated use {@link #nullable()} */
		@Deprecated(forRemoval = false)
		public ColumnBuilder nullValue() {
			return nullable();
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

		@Deprecated(forRemoval = true)
		public TableBuilder end() {
			return table;
		}

		public ColumnDefinition definition() {
			return new ColumnDefinition(name, type, nullable, primary, autoIncrement, unique, extras);
		}
	}
}
