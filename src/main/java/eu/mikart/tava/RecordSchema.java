package eu.mikart.tava;

import eu.mikart.tava.annotation.*;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class RecordSchema {
	private RecordSchema() {
	}

	public static List<ColumnDefinition> columns(Dialect dialect, Class<? extends Record> recordType) {
		if (!recordType.isRecord()) {
			throw new IllegalArgumentException(recordType.getName() + " is not a record");
		}

		List<ColumnDefinition> columns = new ArrayList<>();
		for (RecordComponent component : recordType.getRecordComponents()) {
			Column column = component.getAnnotation(Column.class);
			String name = column != null && !column.name().isBlank() ? column.name() : component.getName();
			boolean primary = component.isAnnotationPresent(Primary.class);
			boolean autoIncrement = component.isAnnotationPresent(AutoIncrement.class);
			if (autoIncrement && (!primary || !isIntegral(component.getType()))) {
				throw unsupported(component, "@AutoIncrement requires an integral @Primary component");
			}
			boolean nullable = !component.getType().isPrimitive()
					&& !primary
					&& !component.isAnnotationPresent(NotNull.class);
			Set<String> extras = component.isAnnotationPresent(DefaultNow.class)
					? Set.of("DEFAULT CURRENT_TIMESTAMP")
					: Set.of();

			columns.add(new ColumnDefinition(
					name,
					sqlType(dialect, component, column),
					nullable,
					primary,
					autoIncrement,
					component.isAnnotationPresent(Unique.class),
					extras
			));
		}
		return List.copyOf(columns);
	}

	private static String sqlType(Dialect dialect, RecordComponent component, Column column) {
		Class<?> type = component.getType();
		if (type == String.class) {
			return dialect.typeString(column == null ? 0 : column.length());
		}
		if (type == UUID.class) return dialect.typeUuid();
		if (type == int.class || type == Integer.class || type == short.class || type == Short.class) {
			return dialect.typeInt();
		}
		if (type == long.class || type == Long.class) return dialect.typeBigInt();
		if (type == boolean.class || type == Boolean.class) return dialect.typeBoolean();
		if (type == Instant.class || type == LocalDateTime.class || type == OffsetDateTime.class) {
			return dialect.typeTimestamp();
		}
		if (type == byte[].class) return dialect.typeBinary();
		if (type == BigDecimal.class) {
			int precision = column == null ? 0 : column.precision();
			int scale = column == null ? 0 : column.scale();
			if (precision < 1) {
				throw unsupported(component, "BigDecimal requires @Column(precision = ..., scale = ...)");
			}
			if (scale < 0 || scale > precision) {
				throw unsupported(component, "decimal scale must be between 0 and precision");
			}
			return "DECIMAL(" + precision + ", " + scale + ")";
		}
		throw unsupported(component, "unsupported Java type " + type.getTypeName());
	}

	private static boolean isIntegral(Class<?> type) {
		return type == int.class || type == Integer.class || type == long.class || type == Long.class
				|| type == short.class || type == Short.class;
	}

	private static IllegalArgumentException unsupported(RecordComponent component, String reason) {
		return new IllegalArgumentException(
				"Cannot infer column '" + component.getName() + "' from "
						+ component.getDeclaringRecord().getName() + ": " + reason
		);
	}
}
