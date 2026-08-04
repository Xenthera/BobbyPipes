package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.craft.CraftPattern;
import com.bobby.bobbypipes.menu.PatternTableMenu;
import com.bobby.bobbypipes.network.payload.SetCraftPatternPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * LP Logistics Crafting Table style: faded ghost matrix/result, real resource buffer + output.
 */
public class PatternTableScreen extends AbstractContainerScreen<PatternTableMenu> {

    private static final int GHOST_OVERLAY = 0x80_FF_FF_FF;

    public PatternTableScreen(PatternTableMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 222);
        this.inventoryLabelY = 128;
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 29;
    }

    private void push(CraftPattern pattern) {
        menu.setPatternLocal(pattern);
        ClientPacketDistributor.sendToServer(new SetCraftPatternPayload(
                menu.pos(), SetCraftPatternPayload.Target.TABLE, pattern));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                ModGuiTextures.PATTERN_TABLE,
                leftPos,
                topPos,
                0.0F,
                0.0F,
                imageWidth,
                imageHeight,
                256,
                256);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractContents(graphics, mouseX, mouseY, partialTick);
        drawGhosts(graphics);
    }

    private void drawGhosts(GuiGraphicsExtractor graphics) {
        CraftPattern pattern = menu.pattern();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int i = row * 3 + col;
                ItemStack stack = i < pattern.inputs().size() ? pattern.inputs().get(i) : ItemStack.EMPTY;
                if (!stack.isEmpty()) {
                    int x = leftPos + GhostCraftingLayout.gridLeft(col) + 1;
                    int y = topPos + GhostCraftingLayout.gridTop(row) + 1;
                    graphics.item(stack, x, y);
                    if (stack.getCount() > 1) {
                        graphics.itemDecorations(font, stack, x, y);
                    }
                    graphics.fill(x, y, x + 16, y + 16, GHOST_OVERLAY);
                }
            }
        }
        // One result well: show faded recipe ghost only while the real output is empty.
        ItemStack result = pattern.primaryOutput();
        if (!result.isEmpty() && menu.isOutputEmpty()) {
            int x = leftPos + GhostCraftingLayout.RESULT_X + 1;
            int y = topPos + GhostCraftingLayout.RESULT_Y + 1;
            graphics.item(result, x, y);
            // Show recipe amount (e.g. 4 planks) even when count is on the ghost stack.
            if (result.getCount() > 1) {
                graphics.itemDecorations(font, result, x, y);
            }
            graphics.fill(x, y, x + 16, y + 16, GHOST_OVERLAY);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // Drawn here rather than via super, which hardcodes vanilla's dark grey.
        graphics.text(font, title, titleLabelX, titleLabelY, PanelStyle.LABEL, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY,
                PanelStyle.LABEL, false);
        // Already translated to panel origin by extractContents.
        graphics.text(font, Component.translatable("gui.bobbypipes.pattern.resources"),
                8, 80, PanelStyle.LABEL, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int i = row * 3 + col;
                int x = leftPos + GhostCraftingLayout.gridLeft(col);
                int y = topPos + GhostCraftingLayout.gridTop(row);
                if (event.x() >= x && event.x() < x + GhostCraftingLayout.SLOT
                        && event.y() >= y && event.y() < y + GhostCraftingLayout.SLOT) {
                    ItemStack carried = minecraft.player != null
                            ? minecraft.player.containerMenu.getCarried()
                            : ItemStack.EMPTY;
                    ItemStack ghost = carried.isEmpty() ? ItemStack.EMPTY : carried.copyWithCount(1);
                    push(menu.pattern().withInput(i, ghost));
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }
}
