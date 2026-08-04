package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.craft.CraftPattern;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

import java.util.OptionalInt;

/**
 * LP crafting-pipe layout: one row of nine ingredient ghosts + result.
 */
public final class CraftingPipeLayout {

    public static final int ROW_X = 8;
    public static final int ROW_Y = 20;
    public static final int RESULT_X = 80;
    public static final int RESULT_Y = 40;
    public static final int SLOT = 18;

    private CraftingPipeLayout() {
    }

    public static int slotX(int index) {
        return ROW_X + index * SLOT;
    }

    public static int slotY() {
        return ROW_Y;
    }

    public static OptionalInt hitTest(double mouseX, double mouseY, int leftPos, int topPos) {
        for (int i = 0; i < 9; i++) {
            int x = leftPos + slotX(i);
            int y = topPos + slotY();
            if (mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT) {
                return OptionalInt.of(i);
            }
        }
        int rx = leftPos + RESULT_X;
        int ry = topPos + RESULT_Y;
        if (mouseX >= rx && mouseX < rx + SLOT && mouseY >= ry && mouseY < ry + SLOT) {
            return OptionalInt.of(GhostCraftingLayout.RESULT_INDEX);
        }
        return OptionalInt.empty();
    }

    public static void drawGhosts(
            GuiGraphicsExtractor graphics, Font font, int leftPos, int topPos, CraftPattern pattern) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = i < pattern.inputs().size() ? pattern.inputs().get(i) : ItemStack.EMPTY;
            if (!stack.isEmpty()) {
                int x = leftPos + slotX(i) + 1;
                int y = topPos + slotY() + 1;
                graphics.item(stack, x, y);
                if (stack.getCount() > 1) {
                    graphics.itemDecorations(font, stack, x, y);
                }
            }
        }
        ItemStack result = pattern.primaryOutput();
        if (!result.isEmpty()) {
            int x = leftPos + RESULT_X + 1;
            int y = topPos + RESULT_Y + 1;
            graphics.item(result, x, y);
            if (result.getCount() > 1) {
                graphics.itemDecorations(font, result, x, y);
            }
        }
    }
}
