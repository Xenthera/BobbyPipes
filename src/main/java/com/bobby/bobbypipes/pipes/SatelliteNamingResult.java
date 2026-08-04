package com.bobby.bobbypipes.pipes;

public enum SatelliteNamingResult {
    SUCCESS,
    BLANK_NAME,
    DUPLICATE_NAME;

    public String langKey() {
        return "gui.bobbypipes.satellite.naming." + name().toLowerCase();
    }
}
