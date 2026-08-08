package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbycore.client.gui.draw.UiDraw;
import com.bobby.bobbycore.client.gui.theme.UiColor;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Tall vertical FE charge bar, matching the energy chest gauge in BobbyChests.
 *
 * <p>Same colour ramp as that gauge on purpose: red when nearly flat, through amber, to green
 * when full. A player who has learned to read one should not have to learn the other. Kept as
 * its own small class here rather than shared, because BobbyChests is only a runtime
 * companion of this mod, not something it compiles against.
 */
final class EnergyGauge {

    private static final int LOW_COLOR = 0xFFE04A2F;
    private static final int MID_COLOR = 0xFFE8B23A;
    private static final int FULL_COLOR = 0xFF54D44A;

    private final int x;
    private final int y;
    private final int width;
    private final int height;

    EnergyGauge(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    static int colorFor(float fraction) {
        float clamped = Math.max(0.0f, Math.min(1.0f, fraction));
        if (clamped < 0.5f) {
            return UiColor.blend(LOW_COLOR, MID_COLOR, clamped / 0.5f);
        }
        return UiColor.blend(MID_COLOR, FULL_COLOR, (clamped - 0.5f) / 0.5f);
    }

    /** Draws the well, the charge, and the border, in screen coordinates. */
    void draw(GuiGraphicsExtractor graphics, UiTheme theme, int amountFe, int capacityFe) {
        // Filled track rather than UiDraw.contentWell: that is deprecated, and it only drew a
        // border, so an empty gauge showed bare panel and read as "no gauge here".
        UiDraw.fill(graphics, x, y, width, height, theme.contentWellFill());
        if (amountFe > 0 && capacityFe > 0) {
            float fraction = Math.min(1.0f, (float) amountFe / capacityFe);
            // At least one pixel, so "nearly empty" still reads as "not empty".
            int filled = Math.max(1, (int) (height * fraction));
            UiDraw.fill(graphics, x, y + height - filled, width, filled, colorFor(fraction));
        }
        UiDraw.border(graphics, theme, x - 1, y - 1, width + 2, height + 2);
    }

    int right() {
        return x + width;
    }

    int centreY() {
        return y + height / 2;
    }

    /** Thousands-separated, so a six-digit buffer is readable at a glance. */
    static String format(int value) {
        return String.format("%,d", value);
    }
}
