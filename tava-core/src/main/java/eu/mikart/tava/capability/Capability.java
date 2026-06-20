package eu.mikart.tava.capability;

import java.util.Map;

public record Capability(SupportLevel level, String detail, Map<String, Long> limits) {
    public Capability {
        detail = detail == null ? "" : detail;
        limits = limits == null ? Map.of() : Map.copyOf(limits);
    }

    public static Capability supported() {
        return new Capability(SupportLevel.SUPPORTED, "", Map.of());
    }

    public static Capability unsupported(String detail) {
        return new Capability(SupportLevel.UNSUPPORTED, detail, Map.of());
    }
}
