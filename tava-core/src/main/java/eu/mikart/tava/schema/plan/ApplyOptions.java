package eu.mikart.tava.schema.plan;

public record ApplyOptions(boolean allowLossy, boolean allowDestructive) {
    public static ApplyOptions safe() {
        return new ApplyOptions(false, false);
    }
}
