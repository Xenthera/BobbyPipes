package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.network.payload.FluidStockPayload;
import com.bobby.bobbypipes.registry.ModItems;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

import java.util.List;
import java.util.Optional;

/**
 * Scrollable fluid grid for the fluid request/supplier screens, same shape and controls as
 * {@link StockItemGrid} but showing fluids instead of items.
 *
 * <p>Icons are the fluid's bucket item ({@code Fluid#getBucket}) rather than a raw texture
 * swatch: this MC version's fluid rendering moved to a baked-model-plus-tint pipeline that
 * is not something a GUI can call into ad hoc, while a bucket icon reuses the exact same
 * {@code graphics.item(...)} path already proven throughout every other screen, and is
 * still immediately recognisable per fluid. Fluids with no registered bucket fall back to
 * the generic fluid parcel icon.
 */
public final class FluidGrid {

    public static final int COLUMNS = 9;
    public static final int VISIBLE_ROWS = 6;
    public static final int SLOT = 18;
    public static final int SCROLLER_WIDTH = 8;

    private final int gridX;
    private final int gridY;
    private final UiTheme theme;
    private final ScrollModel scrollModel = new ScrollModel(VISIBLE_ROWS, 1);
    private final ScrollController scrollController;

    public FluidGrid(int gridX, int gridY, UiTheme theme) {
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

    private void syncModel(int entryCount) {
        scrollModel.setTotalRows(totalRows(entryCount));
        scrollModel.setVisibleRows(VISIBLE_ROWS);
        layoutTrack();
    }

    public int rowIndex(int entryCount) {
        syncModel(entryCount);
        return scrollModel.scrollRows();
    }

    public void clampScroll(int entryCount) {
        syncModel(entryCount);
    }

    public void draw(
            GuiGraphicsExtractor graphics,
            Font font,
            List<FluidStockPayload.Entry> entries,
            FluidResource selected,
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
                FluidStockPayload.Entry entry = entries.get(index);
                int x = gridX + col * SLOT;
                int y = gridY + row * SLOT;
                boolean hovered = mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
                boolean isSelected = entry.fluid().equals(selected);
                if (!isSelected && hovered) {
                    UiDraw.hoverOverlay(graphics, theme, x + 1, y + 1, 16, 16);
                }
                graphics.item(iconFor(entry.fluid()), x + 1, y + 1);
                if (entry.amountMb() > 0) {
                    drawAmount(graphics, font, x + 1, y + 1, entry.amountMb());
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

    static ItemStack iconFor(FluidResource fluid) {
        if (fluid.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Item bucket = fluid.getFluid().getBucket();
        if (bucket == null || bucket == net.minecraft.world.item.Items.AIR) {
            return new ItemStack(ModItems.FLUID_PARCEL.get());
        }
        return new ItemStack(bucket);
    }

    private static void drawAmount(GuiGraphicsExtractor graphics, Font font, int itemX, int itemY, int amount) {
        String text = StockItemGrid.formatAmount(amount);
        float scale = StockItemGrid.amountScale(font, text);
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

    public Optional<FluidStockPayload.Entry> entryAt(
            List<FluidStockPayload.Entry> entries, double mouseX, double mouseY) {
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
}
