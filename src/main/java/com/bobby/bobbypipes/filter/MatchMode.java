package com.bobby.bobbypipes.filter;

/**
 * Whether a filter's entries describe what is let through or what is turned away.
 */
public enum MatchMode {

    /** Only listed things pass. An empty allow list therefore passes nothing. */
    ALLOW("allow"),

    /** Everything except listed things passes. An empty deny list passes everything. */
    DENY("deny");

    private final String id;

    MatchMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public MatchMode opposite() {
        return this == ALLOW ? DENY : ALLOW;
    }

    public static MatchMode byId(String id) {
        for (MatchMode mode : values()) {
            if (mode.id.equals(id)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown match mode: " + id);
    }
}
