package eu.mikart.tava.schema;

import eu.mikart.tava.schema.annotation.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecordSchemasTest {
    @Test
    void createsCanonicalEntityFromRecord() {
        EntityDefinition entity = RecordSchemas.describe(Account.class);
        assertEquals("accounts", entity.name());
        assertTrue(entity.field("id").identity());
        assertEquals(FieldType.string(200), entity.field("email").type());
        assertEquals(FieldType.decimal(12, 2), entity.field("balance").type());
        assertEquals("JSONB", entity.field("metadata").settings().get("postgres.type"));
    }

    @Entity("accounts")
    record Account(
            @Identity UUID id,
            @Required @Unique @Field(length = 200) String email,
            @Field(precision = 12, scale = 2) BigDecimal balance,
            @Generated(GeneratedValue.NOW) Instant createdAt,
            @AdapterSetting(adapter = "postgres", key = "type", value = "JSONB") String metadata
    ) {
    }
}
