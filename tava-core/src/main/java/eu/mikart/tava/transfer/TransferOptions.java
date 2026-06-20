package eu.mikart.tava.transfer;

import eu.mikart.tava.data.EntityRecord;

import java.util.function.UnaryOperator;

public record TransferOptions(
        int batchSize,
        boolean allowLossy,
        boolean applySchema,
        UnaryOperator<EntityRecord> transform,
        ProgressListener progress
) {
    public TransferOptions {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
        transform = transform == null ? UnaryOperator.identity() : transform;
        progress = progress == null ? ProgressListener.NONE : progress;
    }

    public static TransferOptions defaults() {
        return new TransferOptions(500, false, true, UnaryOperator.identity(), ProgressListener.NONE);
    }

    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NONE = (entity, count) -> {
        };

        void transferred(String entity, long count);
    }
}
