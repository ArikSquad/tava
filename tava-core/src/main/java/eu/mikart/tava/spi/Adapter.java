package eu.mikart.tava.spi;

import eu.mikart.tava.capability.Capabilities;
import org.jetbrains.annotations.NotNull;

public interface Adapter extends AutoCloseable {
    @NotNull String name();

    @NotNull Capabilities capabilities();

    @NotNull SchemaManager schemas();

    @NotNull EntityStore entities();

    @NotNull NativeAccess nativeAccess();

    @Override
    void close();
}
