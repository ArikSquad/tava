package eu.mikart.tava.transfer;

import eu.mikart.tava.data.EntityRecord;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Range;

/**
 * Options for one data transfer.
 *
 * @param batchSize maximum records read per page
 * @param allowLossy whether fields absent from the target schema may be discarded
 * @param applySchema whether the target schema is applied before copying
 * @param transform record transformation applied before writes
 * @param progress callback invoked after each transferred record
 */
public record TransferOptions(
        @Range(from = 1, to = Integer.MAX_VALUE - 1) int batchSize,
        boolean allowLossy,
        boolean applySchema,
        UnaryOperator<EntityRecord> transform,
        ProgressListener progress) {
    /**
     * Creates validated transfer options.
     *
     * @param batchSize maximum records read per page
     * @param allowLossy whether fields absent from the target schema may be discarded
     * @param applySchema whether the target schema is applied before copying
     * @param transform record transformation applied before writes
     * @param progress callback invoked after each transferred record
     */
    public TransferOptions {
        if (batchSize < 1 || batchSize == Integer.MAX_VALUE)
            throw new IllegalArgumentException("batchSize must be between 1 and Integer.MAX_VALUE - 1");
        transform = transform == null ? UnaryOperator.identity() : transform;
        progress = progress == null ? ProgressListener.NONE : progress;
    }

    /**
     * Returns conservative defaults for a resumable transfer.
     *
     * @return default transfer options
     */
    public static TransferOptions defaults() {
        return new TransferOptions(500, false, true, UnaryOperator.identity(), ProgressListener.NONE);
    }

    /** Observes transfer progress after each successfully written record. */
    @FunctionalInterface
    public interface ProgressListener {
        /** No-op listener. */
        ProgressListener NONE = (entity, count) -> {};

        /**
         * Reports an updated count for an entity.
         *
         * @param entity entity name
         * @param count records written for this entity so far
         */
        void transferred(String entity, long count);
    }
}
