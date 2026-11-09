package eu.mikart.tava.spi;

import eu.mikart.tava.capability.Capabilities;
import org.jetbrains.annotations.NotNull;

/** Provides the services and metadata for one storage adapter. */
public interface Adapter extends AutoCloseable {
    /**
     * Returns the adapter's stable name.
     *
     * @return adapter name
     */
    @NotNull
    String name();

    /**
     * Returns the features and limits supported by this adapter.
     *
     * @return adapter capabilities
     */
    @NotNull
    Capabilities capabilities();

    /**
     * Returns schema inspection and planning operations.
     *
     * @return schema manager
     */
    @NotNull
    SchemaManager schemas();

    /**
     * Returns data operations for entities stored by this adapter.
     *
     * @return entity store
     */
    @NotNull
    EntityStore entities();

    /**
     * Returns access to adapter-specific driver functionality.
     *
     * @return native access facade
     */
    @NotNull
    NativeAccess nativeAccess();

    /**
     * Returns the transaction manager when transactions are supported.
     *
     * @return transaction manager, which may report that transactions are unsupported
     */
    default @NotNull TransactionManager transactions() {
        return TransactionManager.unsupported();
    }

    /** Closes adapter resources. */
    @Override
    void close();
}
