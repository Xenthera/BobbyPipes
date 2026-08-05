package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.block.entity.PatternTableBlockEntity;
import com.bobby.bobbypipes.craft.CraftPattern;
import com.bobby.bobbypipes.menu.PatternTableMenu;
import com.bobby.bobbypipes.network.payload.SetCraftPatternPayload;
import com.bobby.bobbycore.client.gui.ThemedContainerScreen;
import com.bobby.bobbycore.client.gui.draw.ScreenHeader;
import com.bobby.bobbycore.client.gui.font.BobbyFonts;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.List;
import java.util.Optional;

/**
 * LP Logistics Crafting Table style: faded ghost matrix/result, real resource buffer + output.
 */
public class PatternTableScreen extends ThemedContainerScreen<PatternTableMenu> {

    private ItemStack headerIcon = ItemStack.EMPTY;

    private static final int INV_SLOT_Y = 151 + GuiLayout.CONTENT_TOP_PAD;

    public PatternTableScreen(PatternTableMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, GuiLayout.STANDARD_PANEL_WIDTH, 232 + PipeGui.CONTENT_PAD);
        this.inventoryLabelX = GuiLayout.playerInventoryLabelX(GuiLayout.STANDARD_PANEL_WIDTH);
        this.inventoryLabelY = GuiLayout.playerInventoryLabelY(INV_SLOT_Y);
    }

    @Override
    protected UiTheme uiTheme() {
        return PipeThemes.PATTERN_TABLE;
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = PipeThemes.PATTERN_TABLE.titlePadY();
    }

    private void push(CraftPattern pattern) {
        menu.setPatternLocal(pattern);
        ClientPacketDistributor.sendToServer(new SetCraftPatternPayload(
                menu.pos(), SetCraftPatternPayload.Target.TABLE, pattern));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        PipeGui.drawPanelAndMenuSlots(
                graphics, PipeThemes.PATTERN_TABLE, leftPos, topPos, imageWidth, imageHeight,
                GuiLayout.playerInventoryBandY(INV_SLOT_Y), menu.slots,
                PatternTableBlockEntity.RESOURCE_SLOTS + PatternTableBlockEntity.OUTPUT_SLOTS);
        PipeGui.drawSlotGrid(
                graphics,
                PipeThemes.PATTERN_TABLE,
                leftPos + GhostCraftingLayout.gridLeft(0),
                topPos + GhostCraftingLayout.gridTop(0),
                3,
                3,
                GhostCraftingLayout.SLOT);
        PipeGui.drawSlotFrame(graphics, PipeThemes.PATTERN_TABLE,
                leftPos + GhostCraftingLayout.RESULT_X, topPos + GhostCraftingLayout.RESULT_Y);
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
                    GhostCraftingLayout.drawGhostItem(
                            graphics, font,
                            leftPos + GhostCraftingLayout.gridLeft(col) + 1,
                            topPos + GhostCraftingLayout.gridTop(row) + 1,
                            stack, PipeThemes.PATTERN_TABLE);
                }
            }
        }
        // One result well: show faded recipe ghost only while the real output is empty.
        ItemStack result = pattern.primaryOutput();
        if (!result.isEmpty() && menu.isOutputEmpty()) {
            GhostCraftingLayout.drawGhostItem(
                    graphics, font,
                    leftPos + GhostCraftingLayout.RESULT_X + 1,
                    topPos + GhostCraftingLayout.RESULT_Y + 1,
                    result, PipeThemes.PATTERN_TABLE);
        }
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        ItemStack ghost = GhostCraftingLayout.ghostAt(
                menu.pattern(), mouseX, mouseY, leftPos, topPos, menu.isOutputEmpty());
        if (ghost.isEmpty()) {
            return;
        }
        graphics.setTooltipForNextFrame(
                font, List.of(ghost.getHoverName()), Optional.empty(), ghost, mouseX, mouseY);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // Drawn here rather than via super, which hardcodes vanilla's dark grey.
        ScreenHeader.draw(graphics, font, PipeThemes.PATTERN_TABLE, title, headerIcon, PanelStyle.LABEL);
        graphics.text(font, BobbyFonts.apply(playerInventoryTitle), inventoryLabelX, inventoryLabelY,
                PanelStyle.LABEL, false);
        graphics.text(font, BobbyFonts.translatable("gui.bobbypipes.pattern.resources"),
                8, 80 + PipeGui.CONTENT_PAD, PanelStyle.LABEL, false);
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
