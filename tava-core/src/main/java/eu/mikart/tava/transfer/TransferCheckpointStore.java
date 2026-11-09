package eu.mikart.tava.transfer;

import java.util.Optional;

/**
 * Stores resumable transfer positions. Implementations must exclusively lease each transfer and atomically replace its
 * checkpoint.
 *
 * @since 1.3.0
 */
public interface TransferCheckpointStore {
    /**
     * Acquires exclusive access to one transfer ID.
     *
     * @param transferId stable identity of the transfer configuration
     * @return lease that releases access when closed
     * @throws IllegalArgumentException if {@code transferId} is {@code null} or blank
     * @throws IllegalStateException if another process or thread already holds the lease
     * @since 1.3.0
     */
    Lease acquire(String transferId);

    /**
     * Exclusive access to one transfer's checkpoint.
     *
     * @since 1.3.0
     */
    interface Lease extends AutoCloseable {
        /**
         * Loads the last checkpoint saved while holding this lease.
         *
         * @return the last durably saved position, if any
         * @throws IllegalStateException if this lease has been closed
         * @since 1.3.0
         */
        Optional<TransferCheckpoint> load();
        /**
         * Saves a complete source-page position atomically.
         *
         * @param checkpoint position to persist
         * @throws IllegalArgumentException if the checkpoint belongs to another transfer
         * @throws IllegalStateException if this lease has been closed
         * @since 1.3.0
         */
        void save(TransferCheckpoint checkpoint);
        /**
         * Releases the exclusive transfer lease.
         *
         * @since 1.3.0
         */
        @Override
        void close();
    }
}
