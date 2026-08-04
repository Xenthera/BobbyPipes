package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.network.payload.NetworkStockPayload;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.List;
import java.util.Optional;

/**
 * Refined Storage / creative-inventory style scrollable item grid: 9 columns, row scroll,
 * drag scrollbar, amount overlays, craftable highlight.
 */
public final class StockItemGrid {

    public static final int COLUMNS = 9;
    public static final int VISIBLE_ROWS = 6;
    public static final int SLOT = 18;
    public static final int SCROLLER_WIDTH = 12;
    public static final int SCROLLER_HEIGHT = 15;

    /** RS-style orange backdrop for autocraftable entries (inner 16×16 only). */
    private static final int CRAFTABLE_TINT = 0x55_FF_AA_00;
    private static final int HOVER_TINT = 0x40_FF_FF_FF;

    private static final Identifier SCROLLER_SPRITE =
            Identifier.withDefaultNamespace("container/creative_inventory/scroller");
    private static final Identifier SCROLLER_DISABLED_SPRITE =
            Identifier.withDefaultNamespace("container/creative_inventory/scroller_disabled");

    private final int gridX;
    private final int gridY;
    private float scrollOffs;
    private boolean scrolling;

    public StockItemGrid(int gridX, int gridY) {
        this.gridX = gridX;
        this.gridY = gridY;
    }

    public int width() {
        return COLUMNS * SLOT;
    }

    public int height() {
        return VISIBLE_ROWS * SLOT;
    }

    public int scrollbarX() {
        return gridX + width() + 1;
    }

    public int scrollbarTrackHeight() {
        return height();
    }

    public void resetScroll() {
        scrollOffs = 0.0F;
        scrolling = false;
    }

    public int totalRows(int entryCount) {
        return Math.max(1, Mth.ceil(entryCount / (float) COLUMNS));
    }

    public int maxRowIndex(int entryCount) {
        return Math.max(0, totalRows(entryCount) - VISIBLE_ROWS);
    }

    public boolean canScroll(int entryCount) {
        return maxRowIndex(entryCount) > 0;
    }

    public int rowIndex(int entryCount) {
        return Mth.clamp((int) (scrollOffs * maxRowIndex(entryCount) + 0.5F), 0, maxRowIndex(entryCount));
    }

    public void clampScroll(int entryCount) {
        if (!canScroll(entryCount)) {
            scrollOffs = 0.0F;
        } else {
            scrollOffs = Mth.clamp(scrollOffs, 0.0F, 1.0F);
        }
    }

    public void draw(
            GuiGraphicsExtractor graphics,
            Font font,
            List<NetworkStockPayload.Entry> entries,
            ItemResource selected,
            int mouseX,
            int mouseY) {
        int startRow = rowIndex(entries.size());
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                int index = (startRow + row) * COLUMNS + col;
                if (index >= entries.size()) {
                    continue;
                }
                NetworkStockPayload.Entry entry = entries.get(index);
                int x = gridX + col * SLOT;
                int y = gridY + row * SLOT;
                boolean hovered = mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
                boolean isSelected = entry.item().equals(selected);
                // Inner 16×16 only  -  same as vanilla inventory hover, leaves slot borders.
                if (entry.craftable()) {
                    graphics.fill(x + 1, y + 1, x + 17, y + 17, CRAFTABLE_TINT);
                }
                if (!isSelected && hovered) {
                    graphics.fill(x + 1, y + 1, x + 17, y + 17, HOVER_TINT);
                }
                ItemStack stack = entry.item().toStack(1);
                graphics.item(stack, x + 1, y + 1);
                if (entry.amount() > 0) {
                    drawAmount(graphics, font, x + 1, y + 1, entry.amount());
                }
                if (isSelected) {
                    // 20×20 frame overhangs the 18×18 slot by 1px on each side.
                    int frame = 20;
                    graphics.blit(
                            RenderPipelines.GUI_TEXTURED,
                            ModGuiTextures.REQUEST_SLOT_SELECTED,
                            x - 1,
                            y - 1,
                            0.0F,
                            0.0F,
                            frame,
                            frame,
                            frame,
                            frame);
                }
            }
        }
        drawScrollbar(graphics, entries.size(), mouseX, mouseY);
    }

    /**
     * Count overlay anchored to the bottom-right of the item cell, scaled down as the
     * label grows so large stocks still fit inside the slot.
     */
    private static void drawAmount(GuiGraphicsExtractor graphics, Font font, int itemX, int itemY, int amount) {
        String text = formatAmount(amount);
        float scale = amountScale(font, text);
        float width = font.width(text) * scale;
        float height = 7.0F * scale;
        float x = itemX + 16.0F - width;
        float y = itemY + 16.0F - height;
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        graphics.text(font, text, 0, 0, 0xFF_FF_FF_FF, true);
        graphics.pose().popMatrix();
    }

    /** Shrinks so the formatted count stays within the 16px item cell. */
    static float amountScale(Font font, String text) {
        int width = font.width(text);
        if (width <= 14) {
            return 1.0F;
        }
        return Mth.clamp(14.0F / width, 0.5F, 1.0F);
    }

    private void drawScrollbar(GuiGraphicsExtractor graphics, int entryCount, int mouseX, int mouseY) {
        int x = scrollbarX();
        int y = gridY;
        int trackBottom = y + scrollbarTrackHeight();
        boolean active = canScroll(entryCount);
        Identifier sprite = active ? SCROLLER_SPRITE : SCROLLER_DISABLED_SPRITE;
        int thumbY = active
                ? y + (int) ((trackBottom - y - SCROLLER_HEIGHT) * scrollOffs)
                : y;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, thumbY, SCROLLER_WIDTH, SCROLLER_HEIGHT);
        if (insideScrollbar(mouseX, mouseY)) {
            graphics.requestCursor(active
                    ? (scrolling ? CursorTypes.RESIZE_NS : CursorTypes.POINTING_HAND)
                    : CursorTypes.NOT_ALLOWED);
        }
    }

    public Optional<NetworkStockPayload.Entry> entryAt(
            List<NetworkStockPayload.Entry> entries, double mouseX, double mouseY) {
        if (mouseX < gridX || mouseY < gridY
                || mouseX >= gridX + width() || mouseY >= gridY + height()) {
            return Optional.empty();
        }
        int col = (int) ((mouseX - gridX) / SLOT);
        int row = (int) ((mouseY - gridY) / SLOT);
        if (col < 0 || col >= COLUMNS || row < 0 || row >= VISIBLE_ROWS) {
            return Optional.empty();
        }
        int index = (rowIndex(entries.size()) + row) * COLUMNS + col;
        if (index < 0 || index >= entries.size()) {
            return Optional.empty();
        }
        return Optional.of(entries.get(index));
    }

    public boolean insideScrollbar(double mouseX, double mouseY) {
        int x = scrollbarX();
        return mouseX >= x && mouseX < x + SCROLLER_WIDTH + 2
                && mouseY >= gridY && mouseY < gridY + scrollbarTrackHeight();
    }

    public boolean mouseClicked(MouseButtonEvent event, int entryCount) {
        if (insideScrollbar(event.x(), event.y())) {
            scrolling = canScroll(entryCount);
            if (scrolling) {
                updateScrollFromMouse(event.y(), entryCount);
            }
            return true;
        }
        scrolling = false;
        return false;
    }

    public void mouseReleased() {
        scrolling = false;
    }

    public boolean mouseDragged(MouseButtonEvent event, int entryCount) {
        if (!scrolling || !canScroll(entryCount)) {
            return false;
        }
        updateScrollFromMouse(event.y(), entryCount);
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY, int entryCount) {
        if (!canScroll(entryCount)) {
            return false;
        }
        boolean overGrid = mouseX >= gridX && mouseX < scrollbarX() + SCROLLER_WIDTH + 2
                && mouseY >= gridY && mouseY < gridY + height();
        if (!overGrid) {
            return false;
        }
        int max = maxRowIndex(entryCount);
        int next = Mth.clamp(rowIndex(entryCount) - (int) Math.signum(scrollY), 0, max);
        scrollOffs = max == 0 ? 0.0F : (float) next / max;
        return true;
    }

    private void updateScrollFromMouse(double mouseY, int entryCount) {
        float track = scrollbarTrackHeight() - SCROLLER_HEIGHT;
        if (track <= 0.0F) {
            scrollOffs = 0.0F;
            return;
        }
        scrollOffs = Mth.clamp((float) ((mouseY - gridY - SCROLLER_HEIGHT / 2.0F) / track), 0.0F, 1.0F);
        clampScroll(entryCount);
    }

    /** Compact stack counts like RS (1.2K / 3.4M). */
    public static String formatAmount(int amount) {
        if (amount < 1_000) {
            return String.valueOf(amount);
        }
        if (amount < 1_000_000) {
            double value = amount / 1_000.0;
            return trimDecimal(value) + "K";
        }
        double value = amount / 1_000_000.0;
        return trimDecimal(value) + "M";
    }

    private static String trimDecimal(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.1f", value);
        if (text.endsWith(".0")) {
            return text.substring(0, text.length() - 2);
        }
        return text;
    }
}
