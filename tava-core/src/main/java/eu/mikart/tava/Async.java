package eu.mikart.tava;

import eu.mikart.tava.spi.OperationContext;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;

final class Async {
    private Async() {}

    static <T> CompletableFuture<T> submit(Supplier<T> operation, Executor executor) {
        CompletableFuture<T> result = new CompletableFuture<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            if (!result.isCancelled()) {
                try {
                    T value;
                    try (OperationContext context = OperationContext.open(Duration.ZERO, result::isCancelled)) {
                        value = operation.get();
                    }
                    result.complete(value);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            }
            return null;
        });
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) task.cancel(true);
        });
        executor.execute(task);
        return result;
    }
}
