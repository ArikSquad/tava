package eu.mikart.tava.capability;

import java.util.EnumMap;
import java.util.Map;

public final class Capabilities {
    private final String adapter;
    private final Map<Feature, Capability> features;

    private Capabilities(String adapter, Map<Feature, Capability> features) {
        this.adapter = adapter;
        this.features = Map.copyOf(features);
    }

    public String adapter() {
        return adapter;
    }

    public Capability feature(Feature feature) {
        return features.getOrDefault(feature, Capability.unsupported("Not declared by " + adapter));
    }

    public boolean supports(Feature feature) {
        return feature(feature).level() == SupportLevel.SUPPORTED
                || feature(feature).level() == SupportLevel.EMULATED;
    }

    public Map<Feature, Capability> matrix() {
        return features;
    }

    public static Builder builder(String adapter) {
        return new Builder(adapter);
    }

    public static final class Builder {
        private final String adapter;
        private final EnumMap<Feature, Capability> values = new EnumMap<>(Feature.class);

        private Builder(String adapter) {
            this.adapter = adapter;
        }

        public Builder set(Feature feature, SupportLevel level, String detail) {
            values.put(feature, new Capability(level, detail, Map.of()));
            return this;
        }

        public Builder supported(Feature... features) {
            for (Feature feature : features) values.put(feature, Capability.supported());
            return this;
        }

        public Builder limit(Feature feature, String name, long value) {
            Capability current = values.getOrDefault(feature, Capability.supported());
            var limits = new java.util.HashMap<>(current.limits());
            limits.put(name, value);
            values.put(feature, new Capability(current.level(), current.detail(), limits));
            return this;
        }

        public Capabilities build() {
            return new Capabilities(adapter, values);
        }
    }
}
