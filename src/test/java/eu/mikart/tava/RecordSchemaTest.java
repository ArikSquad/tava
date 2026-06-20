package eu.mikart.tava;

import eu.mikart.tava.annotation.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecordSchemaTest {
	@Test
	void createsAndQueriesTableFromAnnotatedRecord() throws Exception {
		Database database = Database.connect(Dialects.h2("record-" + UUID.randomUUID()));
		database.createTable("product", Product.class);

		UUID id = UUID.randomUUID();
		database.insert("product", row -> {
			row.set("id", id);
			row.set("name", "Keyboard");
			row.set("price", new BigDecimal("129.90"));
		});

		Product product = database.select("product", Product.class, query -> query.eq("id", id)).getFirst();
		assertEquals("Keyboard", product.name());
		assertEquals(new BigDecimal("129.90"), product.price());
	}

	@Test
	void notNullAnnotationCreatesConstraint() throws Exception {
		Database database = Database.connect(Dialects.h2("not-null-" + UUID.randomUUID()));
		database.createTable("required_value", RequiredValue.class);

		assertThrows(SQLException.class, () ->
				database.insert("required_value", row -> row.set("id", 1).set("value", null)));
	}

	@Test
	void rejectsUnsupportedTypesWithComponentName() {
		IllegalArgumentException error = assertThrows(
				IllegalArgumentException.class,
				() -> RecordSchema.columns(Dialects.h2("invalid"), Unsupported.class)
		);
        assertTrue(error.getMessage().contains("payload"));
	}

	record Product(
			@Primary UUID id,
			@NotNull @Unique @Column(length = 160) String name,
			@Column(precision = 10, scale = 2) BigDecimal price,
			@DefaultNow Instant created_at
	) {}

	record RequiredValue(@Primary int id, @NotNull String value) {}

	record Unsupported(@Primary int id, Object payload) {}
}
