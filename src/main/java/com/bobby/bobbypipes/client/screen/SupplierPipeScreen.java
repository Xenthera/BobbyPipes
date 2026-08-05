package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.menu.SupplierPipeMenu;
import com.bobby.bobbypipes.network.payload.SetSupplierRequestsPayload;
import com.bobby.bobbypipes.pipes.SupplierRequests;
import com.bobby.bobbycore.client.gui.ThemedContainerScreen;
import com.bobby.bobbycore.client.gui.draw.ScreenHeader;
import com.bobby.bobbycore.client.gui.font.BobbyFonts;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.List;
import java.util.Optional;

/**
 * Supplier config on the crafting-pipe panel: one row of nine stock targets.
 *
 * <p>Left-click adds the held stack's count; right-click adds one (or clears when
 * empty-handed). A different item replaces the slot. Scroll adjusts the target
 * (shift = ±16).
 */
public class SupplierPipeScreen extends ThemedContainerScreen<SupplierPipeMenu> {

    private static final int HOVER_TINT = 0x40_FF_FF_FF;

    private ItemStack headerIcon = ItemStack.EMPTY;

    public SupplierPipeScreen(SupplierPipeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, GuiLayout.STANDARD_PANEL_WIDTH, SupplierPipeMenu.PANEL_HEIGHT);
        this.inventoryLabelX = GuiLayout.playerInventoryLabelX(GuiLayout.STANDARD_PANEL_WIDTH);
        this.inventoryLabelY = GuiLayout.playerInventoryLabelY(SupplierPipeMenu.INV_SLOT_Y);
        setHelpTooltip(Component.translatable(menu.passive()
                ? "gui.bobbypipes.help.passive_supplier_pipe"
                : "gui.bobbypipes.help.supplier_pipe"));
    }

    @Override
    protected UiTheme uiTheme() {
        return menu.passive() ? PipeThemes.PASSIVE_SUPPLIER : PipeThemes.SUPPLIER;
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = uiTheme().titlePadY();
    }

    /** White, not vanilla's dark grey: this panel is not a stone-coloured vanilla one. */
    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        var theme = menu.passive() ? PipeThemes.PASSIVE_SUPPLIER : PipeThemes.SUPPLIER;
        ScreenHeader.draw(graphics, font, theme, title, headerIcon, PanelStyle.LABEL);
        graphics.text(font, BobbyFonts.apply(playerInventoryTitle), inventoryLabelX, inventoryLabelY,
                PanelStyle.LABEL, false);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        var theme = menu.passive() ? PipeThemes.PASSIVE_SUPPLIER : PipeThemes.SUPPLIER;
        PipeGui.drawPanelAndMenuSlots(
                graphics, theme, leftPos, topPos, imageWidth, imageHeight,
                GuiLayout.playerInventoryBandY(SupplierPipeMenu.INV_SLOT_Y), menu.slots);
        PipeGui.drawSlotRow(
                graphics,
                theme,
                leftPos + CraftingPipeLayout.slotX(0),
                topPos + CraftingPipeLayout.slotY(),
                SupplierRequests.SLOT_COUNT,
                CraftingPipeLayout.SLOT);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractContents(graphics, mouseX, mouseY, partialTick);
        SupplierRequests requests = menu.requests();
        for (int i = 0; i < SupplierRequests.SLOT_COUNT; i++) {
            int x = leftPos + CraftingPipeLayout.slotX(i);
            int y = topPos + CraftingPipeLayout.slotY();
            // Same inset as vanilla inventory slots: highlight the inner 16×16, not the border.
            boolean hovered = mouseX >= x && mouseX < x + CraftingPipeLayout.SLOT
                    && mouseY >= y && mouseY < y + CraftingPipeLayout.SLOT;
            if (hovered) {
                graphics.fill(x + 1, y + 1, x + 17, y + 17, HOVER_TINT);
            }
            ItemStack stack = requests.slot(i);
            if (!stack.isEmpty()) {
                int ix = x + 1;
                int iy = y + 1;
                graphics.item(stack, ix, iy);
                graphics.itemDecorations(font, stack, ix, iy);
            }
        }
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        int slot = slotAt(mouseX, mouseY);
        if (slot < 0) {
            return;
        }
        ItemStack stack = menu.requests().slot(slot);
        if (stack.isEmpty()) {
            return;
        }
        List<Component> lines = List.of(
                stack.getHoverName(),
                Component.translatable("gui.bobbypipes.supplier.target", stack.getCount()));
        graphics.setTooltipForNextFrame(font, lines, Optional.empty(), stack, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int slot = slotAt(event.x(), event.y());
        if (slot < 0) {
            return super.mouseClicked(event, doubleClick);
        }
        ItemStack carried = minecraft.player != null
                ? minecraft.player.containerMenu.getCarried()
                : ItemStack.EMPTY;
        if (carried.isEmpty()) {
            if (event.button() == 1) {
                push(menu.requests().withSlot(slot, ItemStack.EMPTY));
            }
            return true;
        }
        int add = event.button() == 1 ? 1 : carried.getCount();
        if (add <= 0) {
            return true;
        }
        push(menu.requests().withSlot(slot, addToSlot(menu.requests().slot(slot), carried, add)));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int slot = slotAt(mouseX, mouseY);
        if (slot >= 0) {
            ItemStack current = menu.requests().slot(slot);
            if (current.isEmpty()) {
                return true;
            }
            int delta = scrollY > 0 ? 1 : -1;
            if (minecraft != null && minecraft.hasShiftDown()) {
                delta *= 16;
            }
            int next = Mth.clamp(current.getCount() + delta, 1, SupplierRequests.MAX_TARGET);
            ItemStack updated = current.copy();
            updated.setCount(next);
            push(menu.requests().withSlot(slot, updated));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Adds {@code amount} of {@code carried} into {@code current}, replacing on mismatch. */
    private static ItemStack addToSlot(ItemStack current, ItemStack carried, int amount) {
        if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, carried)) {
            return carried.copyWithCount(Mth.clamp(amount, 1, SupplierRequests.MAX_TARGET));
        }
        ItemStack next = current.copy();
        next.setCount(Mth.clamp(current.getCount() + amount, 1, SupplierRequests.MAX_TARGET));
        return next;
    }

    private int slotAt(double mouseX, double mouseY) {
        for (int i = 0; i < SupplierRequests.SLOT_COUNT; i++) {
            int x = leftPos + CraftingPipeLayout.slotX(i);
            int y = topPos + CraftingPipeLayout.slotY();
            if (mouseX >= x && mouseX < x + CraftingPipeLayout.SLOT
                    && mouseY >= y && mouseY < y + CraftingPipeLayout.SLOT) {
                return i;
            }
        }
        return -1;
    }

    private void push(SupplierRequests requests) {
        menu.setRequestsLocal(requests);
        ClientPacketDistributor.sendToServer(new SetSupplierRequestsPayload(menu.pos(), requests));
    }
}
