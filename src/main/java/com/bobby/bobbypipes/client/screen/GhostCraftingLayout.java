package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import com.bobby.bobbycore.client.gui.theme.UiColor;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import com.bobby.bobbypipes.craft.CraftPattern;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/**
 * Vanilla crafting-table coordinates for ghost recipe slots (3x3 + result).
 */
public final class GhostCraftingLayout {

    public static final int GRID_X = 30;
    public static final int GRID_Y = 17 + GuiLayout.CONTENT_TOP_PAD;
    /** Painted result well origin; physical output slot uses +1,+1 for the inner 16x16. */
    public static final int RESULT_X = 124;
    public static final int RESULT_Y = 35 + GuiLayout.CONTENT_TOP_PAD;
    public static final int SLOT = 18;
    /** Sentinel from {@link #hitTest} for the result slot. */
    public static final int RESULT_INDEX = -1;

    private GhostCraftingLayout() {
    }

    /** Soft well-tint fade so ghosts read as programming, not live items. */
    public static int ghostFade(UiTheme theme) {
        return UiColor.withAlpha(theme.contentSlotWell(), 0x78);
    }

    public static void drawGhostItem(
            GuiGraphicsExtractor graphics, Font font, int x, int y, ItemStack stack, UiTheme theme) {
        graphics.item(stack, x, y);
        if (stack.getCount() > 1) {
            graphics.itemDecorations(font, stack, x, y);
        }
        graphics.fill(x, y, x + 16, y + 16, ghostFade(theme));
    }

    public static int gridLeft(int col) {
        return GRID_X + col * SLOT;
    }

    public static int gridTop(int row) {
        return GRID_Y + row * SLOT;
    }

    /**
     * @return input index 0-8, {@link #RESULT_INDEX} for result, or empty if miss
     */
    public static java.util.OptionalInt hitTest(double mouseX, double mouseY, int leftPos, int topPos) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int x = leftPos + gridLeft(col);
                int y = topPos + gridTop(row);
                if (mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT) {
                    return java.util.OptionalInt.of(row * 3 + col);
                }
            }
        }
        int rx = leftPos + RESULT_X;
        int ry = topPos + RESULT_Y;
        if (mouseX >= rx && mouseX < rx + SLOT && mouseY >= ry && mouseY < ry + SLOT) {
            return java.util.OptionalInt.of(RESULT_INDEX);
        }
        return java.util.OptionalInt.empty();
    }

    public static void drawGhosts(
            GuiGraphicsExtractor graphics, Font font, int leftPos, int topPos,
            CraftPattern pattern, UiTheme theme) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int i = row * 3 + col;
                ItemStack stack = i < pattern.inputs().size() ? pattern.inputs().get(i) : ItemStack.EMPTY;
                if (!stack.isEmpty()) {
                    drawGhostItem(graphics, font,
                            leftPos + gridLeft(col) + 1, topPos + gridTop(row) + 1, stack, theme);
                }
            }
        }
        ItemStack result = pattern.primaryOutput();
        if (!result.isEmpty()) {
            drawGhostItem(graphics, font,
                    leftPos + RESULT_X + 1, topPos + RESULT_Y + 1, result, theme);
        }
    }

    /** Ghost stack under the cursor, if any (inputs always; result only when {@code includeResult}). */
    public static ItemStack ghostAt(
            CraftPattern pattern, double mouseX, double mouseY, int leftPos, int topPos,
            boolean includeResult) {
        var hit = hitTest(mouseX, mouseY, leftPos, topPos);
        if (hit.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int index = hit.getAsInt();
        if (index == RESULT_INDEX) {
            return includeResult ? pattern.primaryOutput() : ItemStack.EMPTY;
        }
        if (index < 0 || index >= pattern.inputs().size()) {
            return ItemStack.EMPTY;
        }
        return pattern.inputs().get(index);
    }
}
