package eu.mikart.tava.capability;

import java.util.Map;

/**
 * Support level and adapter-specific limits for one feature.
 *
 * @param level whether the feature is supported directly, emulated, or unsupported
 * @param detail explanatory detail, normalized to an empty string when {@code null}
 * @param limits adapter-specific numeric limits, copied into an immutable map
 */
public record Capability(SupportLevel level, String detail, Map<String, Long> limits) {
    /**
     * Creates a capability declaration.
     *
     * @param level whether the feature is supported directly, emulated, or unsupported
     * @param detail explanatory detail, normalized to an empty string when {@code null}
     * @param limits adapter-specific numeric limits, copied into an immutable map
     */
    public Capability {
        detail = detail == null ? "" : detail;
        limits = limits == null ? Map.of() : Map.copyOf(limits);
    }

    /**
     * Creates a capability with supported level and no additional detail or limits.
     *
     * @return a supported capability
     */
    public static Capability supported() {
        return new Capability(SupportLevel.SUPPORTED, "", Map.of());
    }

    /**
     * Creates an unsupported capability with an optional explanation.
     *
     * @param detail explanation of why the feature is unsupported
     * @return an unsupported capability
     */
    public static Capability unsupported(String detail) {
        return new Capability(SupportLevel.UNSUPPORTED, detail, Map.of());
    }
}
