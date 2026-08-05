package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbycore.client.gui.theme.BobbyThemes;

/**
 * Domain colours shared by BobbyPipes screens. Panel chrome comes from BobbyCore themes;
 * marker greens/reds stay as pipe-arm connection colours.
 */
final class PanelStyle {

    /** Panel titles and the player inventory label. */
    static final int LABEL = BobbyThemes.BOBBY_DARK.labelPrimary();

    /**
     * The pipe's own connection colours, lifted pixel for pixel from the arm textures.
     *
     * <p>Green and red already mean routed and not routed everywhere else the player looks,
     * so a screen saying the same thing says it in the same two colours rather than
     * inventing a third pair.
     */
    static final int MARKER_GREEN = 0xFF_9C_DB_43;
    static final int MARKER_RED = 0xFF_D4_4F_4F;

    private PanelStyle() {
    }
}
