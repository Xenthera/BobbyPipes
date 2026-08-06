package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbycore.client.gui.theme.BobbyThemes;
import com.bobby.bobbycore.client.gui.theme.UiTheme;

/**
 * Per-pipe-role content tints.
 *
 * <p>A role's colour (Provider, Request, Supplier, ...) is shared across every resource
 * kind (item, energy, fluid) that has that role, rather than each kind getting its own
 * palette. What tells an energy or fluid pipe apart in-world is the coloured edge overlay
 * {@link com.bobby.bobbypipes.client.texture.PipeCompositeSource} composites onto it, not a
 * different tint, the same role always reads as the same colour. These constants are the
 * single source of truth for both this GUI tint and the in-world block tint baked by the
 * pipe texture atlas ({@code assets/bobbypipes/atlases/blocks.json}), keep the hex values
 * there in sync with these if either changes.
 */
final class PipeThemes {
    /** Basic pipe - saturated blue. */
    static final UiTheme BASIC = BobbyThemes.tinted(0x2351A8);
    /** Provider role - warm copper/orange. */
    static final UiTheme PROVIDER = BobbyThemes.tinted(0xC16532);
    /** Crafting pipe - purple. */
    static final UiTheme CRAFTING = BobbyThemes.tinted(0x792994);
    /** Supplier role - indigo. */
    static final UiTheme SUPPLIER = BobbyThemes.tinted(0x3B2994);
    /** Passive supplier - cyan. */
    static final UiTheme PASSIVE_SUPPLIER = BobbyThemes.tinted(0x297A94);
    /** Satellite - rose/magenta. */
    static final UiTheme SATELLITE = BobbyThemes.tinted(0x94295F);
    /** Request role / autocraft stock UI - teal green. */
    static final UiTheme REQUEST = BobbyThemes.tinted(0x29946D);
    /** Pattern table - crafting-table wood. */
    static final UiTheme PATTERN_TABLE = BobbyThemes.tinted(0x693F27);
    /** Autocraft monitor - mint-teal (job board). */
    static final UiTheme AUTOCRAFT_MONITOR = BobbyThemes.tinted(0x2F9A92);

    private PipeThemes() {
    }
}
