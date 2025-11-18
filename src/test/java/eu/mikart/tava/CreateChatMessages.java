package eu.mikart.tava;

import eu.mikart.tava.migration.Migration;
import eu.mikart.tava.schema.SchemaBuilder;

public final class CreateChatMessages extends Migration {
	public CreateChatMessages() {
		super(1, "2025-01-create_chat_messages");
	}

	@Override
	public void up(SchemaBuilder schema) {
		schema.createTable("chat_message", table -> {
			table.uuid("unique_id").primary().end();
			table.string("content").nullAllowed(false).end();
			table.instant("created_at").defaultNow().nullAllowed(false).end();
		});
	}
}
