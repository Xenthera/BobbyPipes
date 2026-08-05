package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.network.payload.NetworkStockPayload;
import com.bobby.bobbycore.client.gui.draw.SlotChrome;
import com.bobby.bobbycore.client.gui.draw.UiDraw;
import com.bobby.bobbycore.client.gui.scroll.ScrollController;
import com.bobby.bobbycore.client.gui.scroll.ScrollModel;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.List;
import java.util.Optional;

/**
 * Refined Storage / creative-inventory style scrollable item grid: 9 columns, row scroll,
 * drag scrollbar, amount overlays, craftable highlight. Uses BobbyCore {@link ScrollController}.
 */
public final class StockItemGrid {

    public static final int COLUMNS = 9;
    public static final int VISIBLE_ROWS = 6;
    public static final int SLOT = 18;
    public static final int SCROLLER_WIDTH = 8;

    private static final int CRAFTABLE_TINT = 0x55_FF_AA_00;

    private final int gridX;
    private final int gridY;
    private final UiTheme theme;
    private final ScrollModel scrollModel = new ScrollModel(VISIBLE_ROWS, 1);
    private final ScrollController scrollController;

    public StockItemGrid(int gridX, int gridY) {
        this(gridX, gridY, PipeThemes.REQUEST);
    }

    public StockItemGrid(int gridX, int gridY, UiTheme theme) {
        this.gridX = gridX;
        this.gridY = gridY;
        this.theme = theme;
        this.scrollController = new ScrollController(scrollModel).setTheme(theme);
        layoutTrack();
    }

    private void layoutTrack() {
        scrollController.setTrackBounds(scrollbarX(), gridY, SCROLLER_WIDTH, height());
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

    public void resetScroll() {
        scrollModel.setScrollRows(0);
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
        syncModel(entryCount);
        return scrollModel.scrollRows();
    }

    private void syncModel(int entryCount) {
        scrollModel.setTotalRows(totalRows(entryCount));
        scrollModel.setVisibleRows(VISIBLE_ROWS);
        layoutTrack();
    }

    public void clampScroll(int entryCount) {
        syncModel(entryCount);
    }

    public void draw(
            GuiGraphicsExtractor graphics,
            Font font,
            List<NetworkStockPayload.Entry> entries,
            ItemResource selected,
            int mouseX,
            int mouseY) {
        syncModel(entries.size());
        int startRow = scrollModel.scrollRows();
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
                if (entry.craftable()) {
                    graphics.fill(x + 1, y + 1, x + 17, y + 17, CRAFTABLE_TINT);
                }
                if (!isSelected && hovered) {
                    UiDraw.hoverOverlay(graphics, theme, x + 1, y + 1, 16, 16);
                }
                ItemStack stack = entry.item().toStack(1);
                graphics.item(stack, x + 1, y + 1);
                if (entry.amount() > 0) {
                    drawAmount(graphics, font, x + 1, y + 1, entry.amount());
                }
                if (isSelected) {
                    SlotChrome.drawSelected(graphics, theme, x, y);
                }
            }
        }
        scrollController.draw(graphics, mouseX, mouseY);
        if (scrollController.isMouseOverTrack(mouseX, mouseY)) {
            graphics.requestCursor(scrollModel.canScroll()
                    ? (scrollController.isDragging() ? CursorTypes.RESIZE_NS : CursorTypes.POINTING_HAND)
                    : CursorTypes.NOT_ALLOWED);
        }
    }

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

    static float amountScale(Font font, String text) {
        int width = font.width(text);
        if (width <= 14) {
            return 1.0F;
        }
        return Mth.clamp(14.0F / width, 0.5F, 1.0F);
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
        return scrollController.isMouseOverTrack(mouseX, mouseY);
    }

    public boolean mouseClicked(MouseButtonEvent event, int entryCount) {
        syncModel(entryCount);
        return scrollController.mouseClicked(event);
    }

    public void mouseReleased() {
        scrollController.endDrag();
    }

    public void mouseReleased(MouseButtonEvent event) {
        scrollController.mouseReleased(event);
    }

    public boolean mouseDragged(MouseButtonEvent event, int entryCount) {
        syncModel(entryCount);
        return scrollController.mouseDragged(event);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY, int entryCount) {
        syncModel(entryCount);
        boolean overGrid = mouseX >= gridX && mouseX < scrollbarX() + SCROLLER_WIDTH + 2
                && mouseY >= gridY && mouseY < gridY + height();
        return scrollController.mouseScrolled(mouseX, mouseY, scrollY, overGrid);
    }

    public static String formatAmount(int amount) {
        if (amount < 1_000) {
            return String.valueOf(amount);
        }
        if (amount < 1_000_000) {
            return trimDecimal(amount / 1_000.0) + "K";
        }
        return trimDecimal(amount / 1_000_000.0) + "M";
    }

    private static String trimDecimal(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.1f", value);
        if (text.endsWith(".0")) {
            return text.substring(0, text.length() - 2);
        }
        return text;
    }
}
