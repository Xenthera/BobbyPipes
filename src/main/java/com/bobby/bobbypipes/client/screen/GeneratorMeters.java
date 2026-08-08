package com.bobby.bobbypipes.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

/**
 * Shared meter drawing for the generator screens, so the fuel and twerk charge bars are
 * literally the same bar rather than two that drift apart.
 */
final class GeneratorMeters {

    private static final int TRACK = 0xFF1A1A1A;
    private static final int CHARGE = 0xFFC4A035;
    private static final int CHARGE_FULL = 0xFF6BD46B;
    private static final int BURN = 0xFFE8761C;

    private GeneratorMeters() {
    }

    /** Horizontal charge bar; turns green at full so "topped up" reads at a glance. */
    static void drawChargeBar(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
                              int energy, int capacity) {
        graphics.fill(x, y, x + w, y + h, TRACK);
        if (capacity <= 0 || energy <= 0) {
            return;
        }
        float ratio = Mth.clamp(energy / (float) capacity, 0f, 1f);
        int filled = Math.round(w * ratio);
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + h, ratio >= 1f ? CHARGE_FULL : CHARGE);
        }
    }

    /**
     * Vertical burn gauge that empties as the current fuel item is consumed.
     *
     * <p>Drawn from the bottom up like a furnace flame, so a nearly spent item is visibly
     * nearly spent without reading a number.
     */
    static void drawBurnGauge(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
                              int burnTicks, int burnTicksTotal) {
        graphics.fill(x, y, x + w, y + h, TRACK);
        if (burnTicks <= 0 || burnTicksTotal <= 0) {
            return;
        }
        float ratio = Mth.clamp(burnTicks / (float) burnTicksTotal, 0f, 1f);
        int lit = Math.round(h * ratio);
        if (lit > 0) {
            graphics.fill(x, y + h - lit, x + w, y + h, BURN);
        }
    }
}
