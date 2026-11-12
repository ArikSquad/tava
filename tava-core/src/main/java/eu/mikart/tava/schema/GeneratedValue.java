package eu.mikart.tava.schema;

/** Describes how a field value is generated. */
public enum GeneratedValue {
    /** No value generation is requested. */
    NONE,
    /** The storage adapter generates an identity value. */
    IDENTITY,
    /** Record mapping generates a UUID value. */
    UUID,
    /** The storage adapter supplies the current time. */
    NOW
}
