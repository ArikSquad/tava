package eu.mikart.tava.spi;

import eu.mikart.tava.TavaException.Capability;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;

/** Executes work against a transaction-scoped entity store. */
public interface TransactionManager {
    /**
     * Runs a callback within a storage transaction.
     *
     * @param <R> result type
     * @param callback work to run with the transaction's entity store
     * @return value returned by the callback
     */
    <R> R transaction(@NotNull Function<EntityStore, R> callback);

    /**
     * Creates a transaction manager that reports transactions as unsupported.
     *
     * @return manager that throws {@link Capability} when a transaction is requested
     */
    static TransactionManager unsupported() {
        return new TransactionManager() {
            @Override
            public <R> R transaction(@NotNull Function<EntityStore, R> callback) {
                throw new Capability("Adapter does not support transactions");
            }
        };
    }
}
