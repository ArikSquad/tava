package eu.mikart.tava.transfer;

import eu.mikart.tava.Tava;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.plan.ApplyOptions;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DataTransfer {
    public static @NotNull TransferReport copy(@NotNull final Tava source, @NotNull final Tava target, @NotNull final Schema schema, @NotNull final TransferOptions options) {
        if (options.applySchema()) {
            target.plan(schema).apply(new ApplyOptions(options.allowLossy(), false));
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        List<TransferIssue> issues = new ArrayList<>();
        for (var entity : schema.entities()) {
            long count = 0;
            String cursor = null;
            do {
                var page = source.records(entity.name()).find(Query.builder()
                    .limit(options.batchSize()).cursor(cursor).build());
                for (EntityRecord record : page.items()) {
                    try {
                        target.records(entity.name()).insert(options.transform().apply(record));
                        count++;
                        options.progress().transferred(entity.name(), count);
                    } catch (RuntimeException failure) {
                        issues.add(new TransferIssue(entity.name(), null, TransferIssue.Severity.ERROR,
                            failure.getMessage()));
                        counts.put(entity.name(), count);
                        return new TransferReport(counts, issues, false);
                    }
                }
                cursor = page.nextCursor();
            } while (cursor != null);
            counts.put(entity.name(), count);
        }
        return new TransferReport(counts, issues, true);
    }
}
