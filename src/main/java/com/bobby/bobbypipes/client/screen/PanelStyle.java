package com.bobby.bobbypipes.client.screen;

/**
 * Colours shared by the mod's container screens.
 *
 * <p>Vanilla hardcodes its panel labels to a dark grey chosen for the stone-coloured
 * vanilla backgrounds. Every panel in this mod is a saturated colour instead, where that
 * grey reads as a smudge, so the screens draw their own labels in white rather than
 * calling {@code super.extractLabels}.
 */
final class PanelStyle {

    /** Panel titles and the player inventory label. */
    static final int LABEL = 0xFF_FF_FF_FF;

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
