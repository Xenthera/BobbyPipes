package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbycore.client.gui.theme.BobbyThemes;
import com.bobby.bobbycore.client.gui.theme.UiTheme;

/**
 * Per-pipe content tints sampled from block textures.
 */
final class PipeThemes {
    /** Basic pipe - saturated blue from {@code basic_pipe.png}. */
    static final UiTheme BASIC = BobbyThemes.tinted(0x2351A8);
    /** Provider pipe - warm copper/orange from {@code provider_pipe.png}. */
    static final UiTheme PROVIDER = BobbyThemes.tinted(0xC16532);
    /** Crafting pipe - purple. */
    static final UiTheme CRAFTING = BobbyThemes.tinted(0x792994);
    /** Active supplier - indigo. */
    static final UiTheme SUPPLIER = BobbyThemes.tinted(0x3B2994);
    /** Passive supplier - cyan. */
    static final UiTheme PASSIVE_SUPPLIER = BobbyThemes.tinted(0x297A94);
    /** Satellite - rose/magenta. */
    static final UiTheme SATELLITE = BobbyThemes.tinted(0x94295F);
    /** Request / autocraft stock UI - teal green. */
    static final UiTheme REQUEST = BobbyThemes.tinted(0x29946D);
    /** Pattern table - crafting-table wood. */
    static final UiTheme PATTERN_TABLE = BobbyThemes.tinted(0x693F27);
    /** Autocraft monitor - mint-teal (job board). */
    static final UiTheme AUTOCRAFT_MONITOR = BobbyThemes.tinted(0x2F9A92);

    private PipeThemes() {
    }
}
