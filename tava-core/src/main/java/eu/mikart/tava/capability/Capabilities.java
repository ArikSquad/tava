package eu.mikart.tava.capability;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Immutable capability declarations made by one adapter. */
public final class Capabilities {
    private final String adapter;
    private final Map<Feature, Capability> features;

    private Capabilities(String adapter, Map<Feature, Capability> features) {
        this.adapter = adapter;
        this.features = Map.copyOf(features);
    }

    /**
     * Returns the name of the adapter that declared these capabilities.
     *
     * @return adapter name
     */
    public String adapter() {
        return adapter;
    }

    /**
     * Returns the declared support for a feature.
     *
     * @param feature feature to inspect
     * @return declared capability, or an unsupported capability if none was declared
     */
    public Capability feature(Feature feature) {
        return features.getOrDefault(feature, Capability.unsupported("Not declared by " + adapter));
    }

    /**
     * Reports whether a feature is supported directly or through emulation.
     *
     * @param feature feature to inspect
     * @return {@code true} when the feature is supported or emulated
     */
    public boolean supports(Feature feature) {
        return feature(feature).level() == SupportLevel.SUPPORTED
                || feature(feature).level() == SupportLevel.EMULATED;
    }

    /**
     * Returns all declared capabilities.
     *
     * @return immutable map of declared capabilities
     */
    public Map<Feature, Capability> matrix() {
        return features;
    }

    /**
     * Starts building capability declarations for an adapter.
     *
     * @param adapter adapter name
     * @return a builder for that adapter's capabilities
     */
    public static Builder builder(String adapter) {
        return new Builder(adapter);
    }

    /** Builds an immutable capability matrix for one adapter. */
    public static final class Builder {
        private final String adapter;
        private final EnumMap<Feature, Capability> values = new EnumMap<>(Feature.class);

        private Builder(String adapter) {
            this.adapter = adapter;
        }

        /**
         * Declares a feature's support level and explanatory detail.
         *
         * @param feature feature being declared
         * @param level support level
         * @param detail explanation of the declaration
         * @return this builder
         */
        public Builder set(Feature feature, SupportLevel level, String detail) {
            values.put(feature, new Capability(level, detail, Map.of()));
            return this;
        }

        /**
         * Declares one or more features as supported.
         *
         * @param features features supported by the adapter
         * @return this builder
         */
        public Builder supported(Feature... features) {
            for (Feature feature : features) values.put(feature, Capability.supported());
            return this;
        }

        /**
         * Adds or replaces a numeric limit for a feature.
         *
         * @param feature feature with the limit
         * @param name limit name
         * @param value limit value
         * @return this builder
         */
        public Builder limit(Feature feature, String name, long value) {
            Capability current = values.getOrDefault(feature, Capability.supported());
            var limits = new HashMap<>(current.limits());
            limits.put(name, value);
            values.put(feature, new Capability(current.level(), current.detail(), limits));
            return this;
        }

        /**
         * Builds the immutable capability declarations.
         *
         * @return capability declarations for the configured adapter
         */
        public Capabilities build() {
            return new Capabilities(adapter, values);
        }
    }
}
