package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbycore.client.gui.ThemedContainerScreen;
import com.bobby.bobbycore.client.gui.draw.ScreenHeader;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import com.bobby.bobbycore.client.gui.widget.UiButton;
import com.bobby.bobbycore.client.gui.widget.UiTextBox;
import com.bobby.bobbypipes.client.ClientFluidRequestGui;
import com.bobby.bobbypipes.menu.FluidSupplierPipeMenu;
import com.bobby.bobbypipes.network.payload.FluidStockPayload;
import com.bobby.bobbypipes.network.payload.SetFluidSupplierTargetPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

import java.util.List;
import java.util.Optional;

/**
 * Fluid supplier pipe screen: pick a target fluid from the network catalog (same grid the
 * Request screen uses) and a target amount this pipe keeps its attached tank filled to.
 *
 * <p>Picking from the network catalog rather than a drag-a-bucket-in ghost slot (the item
 * Supplier's way) is a deliberate simplification: it reuses the grid built for Request
 * outright, and in practice a Supplier's target is almost always something already
 * provided somewhere on the network anyway.
 */
public class FluidSupplierPipeScreen extends ThemedContainerScreen<FluidSupplierPipeMenu> {

    private static final int CONTENT_X = GuiLayout.contentSlotOriginX();
    private static final int GRID_GAP = 4;
    private static final int ACTION_H = 16;
    private static final int AMOUNT_W = 60;
    private static final int TOOL_GAP = 3;

    private FluidGrid grid;
    private UiTextBox amount;
    private FluidResource selected = FluidResource.EMPTY;
    private ItemStack headerIcon = ItemStack.EMPTY;

    public FluidSupplierPipeScreen(FluidSupplierPipeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, panelWidth(), panelHeight());
        this.inventoryLabelY = 1000;
        setHelpTooltip(Component.translatable("gui.bobbypipes.help.fluid_supplier_pipe"));
        this.selected = menu.targetFluid();
    }

    @Override
    protected UiTheme uiTheme() {
        return PipeThemes.SUPPLIER;
    }

    private static int panelWidth() {
        return CONTENT_X + FluidGrid.COLUMNS * FluidGrid.SLOT + 1 + FluidGrid.SCROLLER_WIDTH + CONTENT_X;
    }

    private static int panelHeight() {
        int gridY = gridY(PipeThemes.SUPPLIER);
        int actionY = gridY + FluidGrid.VISIBLE_ROWS * FluidGrid.SLOT + GRID_GAP;
        return actionY + ACTION_H + GuiLayout.CONTENT_BOTTOM_PAD;
    }

    private static int gridY(UiTheme theme) {
        return theme.headerHeight() + GRID_GAP;
    }

    private int gridY() {
        return gridY(PipeThemes.SUPPLIER);
    }

    private int actionY() {
        return gridY() + FluidGrid.VISIBLE_ROWS * FluidGrid.SLOT + GRID_GAP;
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = PipeThemes.SUPPLIER.titlePadY();

        grid = new FluidGrid(leftPos + CONTENT_X, topPos + gridY(), PipeThemes.SUPPLIER);

        int ay = actionY();
        amount = UiTextBox.builder(font, Component.translatable("gui.bobbypipes.fluid_supplier.target"))
                .bounds(leftPos + CONTENT_X, topPos + ay, AMOUNT_W, ACTION_H)
                .theme(PipeThemes.SUPPLIER)
                .maxLength(9)
                .filter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit))
                .value(Integer.toString(menu.targetMb()))
                .build();
        addRenderableWidget(amount);

        int setX = leftPos + CONTENT_X + AMOUNT_W + TOOL_GAP;
        int setW = leftPos + imageWidth - CONTENT_X - setX;
        UiButton setButton = UiButton.builder(
                        Component.translatable("gui.bobbypipes.fluid_supplier.set"),
                        button -> commitTarget())
                .bounds(setX, topPos + ay, Math.max(48, setW), ACTION_H)
                .build()
                .setTheme(PipeThemes.SUPPLIER);
        addRenderableWidget(setButton);
    }

    private void commitTarget() {
        int value = parseAmount();
        menu.setTargetLocal(selected, value);
        ClientPacketDistributor.sendToServer(
                new SetFluidSupplierTargetPayload(menu.pos(), selected, value));
    }

    private int parseAmount() {
        if (amount == null) {
            return 0;
        }
        String text = amount.getValue().trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(text));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private List<FluidStockPayload.Entry> stock() {
        return ClientFluidRequestGui.stock();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (grid != null) {
            grid.draw(graphics, font, stock(), selected, mouseX, mouseY);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ScreenHeader.draw(graphics, font, PipeThemes.SUPPLIER, title, headerIcon, PanelStyle.LABEL);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        PipeGui.drawPanelAndMenuSlots(
                graphics, PipeThemes.SUPPLIER, leftPos, topPos, imageWidth, imageHeight, menu.slots);
        PipeGui.drawSlotGrid(
                graphics,
                PipeThemes.SUPPLIER,
                leftPos + CONTENT_X,
                topPos + gridY(),
                FluidGrid.COLUMNS,
                FluidGrid.VISIBLE_ROWS,
                FluidGrid.SLOT);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        List<FluidStockPayload.Entry> entries = stock();
        if (grid != null && grid.mouseClicked(event, entries.size())) {
            return true;
        }
        if (grid != null) {
            Optional<FluidStockPayload.Entry> hit = grid.entryAt(entries, event.x(), event.y());
            if (hit.isPresent()) {
                selected = hit.get().fluid();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (grid != null) {
            grid.mouseReleased(event);
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (grid != null && grid.mouseDragged(event, stock().size())) {
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (grid != null && grid.mouseScrolled(mouseX, mouseY, scrollY, stock().size())) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
