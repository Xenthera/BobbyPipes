package com.bobby.bobbypipes.pipes;

/**
 * Result of setting a link-pipe channel from the wrench UI.
 */
public enum LinkChannelResult {
    SUCCESS("gui.bobbypipes.link.ok"),
    CHANNEL_FULL("gui.bobbypipes.link.channel_full"),
    INVALID_CHANNEL("gui.bobbypipes.link.invalid_channel");

    private final String langKey;

    LinkChannelResult(String langKey) {
        this.langKey = langKey;
    }

    public String langKey() {
        return langKey;
    }
}
