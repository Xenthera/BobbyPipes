package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbycore.client.gui.ThemedScreenChrome;
import com.bobby.bobbycore.client.gui.draw.SlotChrome;
import com.bobby.bobbycore.client.gui.draw.UiDraw;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import com.bobby.bobbycore.client.gui.theme.BobbyThemes;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import com.bobby.bobbycore.client.gui.theme.UiTheme.SlotStyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.inventory.Slot;

/**
 * BobbyPipes screens share BobbyCore chrome; each screen picks a {@link PipeThemes} tint.
 */
final class PipeGui {
    /** Fallback geometry tokens. Prefer a {@link PipeThemes} tint for drawing. */
    static final UiTheme THEME = BobbyThemes.BOBBY_DARK;
    static final int CONTENT_TOP = GuiLayout.slottedContentTop();
    static final int CONTENT_PAD = GuiLayout.CONTENT_TOP_PAD;

    private PipeGui() {
    }

    static void drawPanelAndMenuSlots(
            GuiGraphicsExtractor graphics,
            UiTheme theme,
            int leftPos,
            int topPos,
            int imageWidth,
            int imageHeight,
            int inventoryTopY,
            Iterable<Slot> slots,
            int contentSlotCount) {
        ThemedScreenChrome.drawPanel(
                graphics, theme, leftPos, topPos, imageWidth, imageHeight, inventoryTopY);
        drawContentPaneBorder(graphics, theme, leftPos, topPos, imageWidth, imageHeight, inventoryTopY);
        SlotChrome.drawMenuSlots(graphics, theme, leftPos, topPos, slots, contentSlotCount);
    }

    static void drawPanelAndMenuSlots(
            GuiGraphicsExtractor graphics,
            UiTheme theme,
            int leftPos,
            int topPos,
            int imageWidth,
            int imageHeight,
            int inventoryTopY,
            Iterable<Slot> slots) {
        drawPanelAndMenuSlots(graphics, theme, leftPos, topPos, imageWidth, imageHeight, inventoryTopY, slots, 0);
    }

    static void drawPanelAndMenuSlots(
            GuiGraphicsExtractor graphics,
            UiTheme theme,
            int leftPos,
            int topPos,
            int imageWidth,
            int imageHeight,
            Iterable<Slot> slots) {
        drawPanelAndMenuSlots(graphics, theme, leftPos, topPos, imageWidth, imageHeight, -1, slots, 0);
    }

    /** 1px border around the content band (no recessed well). */
    static void drawContentPaneBorder(
            GuiGraphicsExtractor graphics,
            UiTheme theme,
            int leftPos,
            int topPos,
            int imageWidth,
            int imageHeight,
            int inventoryTopY) {
        UiDraw.contentPaneBorder(graphics, theme, leftPos, topPos, imageWidth, imageHeight, inventoryTopY);
    }

    /** @deprecated Use {@link #drawContentPaneBorder}. */
    @Deprecated
    static void drawContentPaneWell(
            GuiGraphicsExtractor graphics,
            UiTheme theme,
            int leftPos,
            int topPos,
            int imageWidth,
            int imageHeight,
            int inventoryTopY) {
        drawContentPaneBorder(graphics, theme, leftPos, topPos, imageWidth, imageHeight, inventoryTopY);
    }

    static void drawSlotFrame(GuiGraphicsExtractor graphics, UiTheme theme, int frameX, int frameY) {
        SlotChrome.drawFrame(graphics, theme, SlotStyle.CONTENT, frameX, frameY);
    }

    static void drawSlotFrame(
            GuiGraphicsExtractor graphics, UiTheme theme, SlotStyle style, int frameX, int frameY) {
        SlotChrome.drawFrame(graphics, theme, style, frameX, frameY);
    }

    static void drawSlotFrame(
            GuiGraphicsExtractor graphics,
            UiTheme theme,
            int frameX,
            int frameY,
            boolean borderRight,
            boolean borderBottom) {
        SlotChrome.drawFrame(
                graphics, theme, SlotStyle.CONTENT, frameX, frameY, false, borderRight, borderBottom);
    }

    static void drawSlotGrid(
            GuiGraphicsExtractor graphics,
            UiTheme theme,
            int originX,
            int originY,
            int columns,
            int rows,
            int stride) {
        SlotChrome.drawGrid(graphics, theme, SlotStyle.CONTENT, originX, originY, columns, rows, stride);
    }

    static void drawSlotRow(
            GuiGraphicsExtractor graphics, UiTheme theme, int originX, int originY, int count, int stride) {
        SlotChrome.drawRow(graphics, theme, SlotStyle.CONTENT, originX, originY, count, stride);
    }

    static void drawSlotAtItem(GuiGraphicsExtractor graphics, UiTheme theme, int itemX, int itemY) {
        SlotChrome.drawAtItem(graphics, theme, SlotStyle.CONTENT, itemX, itemY);
    }
}
