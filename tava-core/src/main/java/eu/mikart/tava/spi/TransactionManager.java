package eu.mikart.tava.spi;

import org.jetbrains.annotations.NotNull;
import java.util.function.Function;

public interface TransactionManager {
    <R> R transaction(@NotNull Function<EntityStore, R> callback);
    static TransactionManager unsupported() { return new TransactionManager() {
        @Override public <R> R transaction(@NotNull Function<EntityStore, R> callback) {
            throw new eu.mikart.tava.TavaException.Capability("Adapter does not support transactions");
        }
    }; }
}
