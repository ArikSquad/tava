package eu.mikart.tava.schema.plan;

/** Describes whether a planned schema change is safe to apply and what opt-in it requires. */
public enum ChangeRisk {
    /** Change can be applied with the default options. */
    SAFE,
    /** Change may lose information and requires {@code allowLossy}. */
    LOSSY,
    /** Change may remove or replace stored data and requires {@code allowDestructive}. */
    DESTRUCTIVE,
    /** Adapter cannot apply this change. */
    UNSUPPORTED
}
