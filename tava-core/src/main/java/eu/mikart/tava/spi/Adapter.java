package eu.mikart.tava.spi;

import eu.mikart.tava.capability.Capabilities;

public interface Adapter extends AutoCloseable {
    String name();

    Capabilities capabilities();

    SchemaManager schemas();

    EntityStore entities();

    NativeAccess nativeAccess();

    @Override
    void close();
}
