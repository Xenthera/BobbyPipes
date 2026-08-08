package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.menu.FuelGeneratorMenu;
import com.bobby.bobbycore.client.gui.ThemedContainerScreen;
import com.bobby.bobbycore.client.gui.draw.ScreenHeader;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Fuel generator UI in the usual generator arrangement: a tall charge gauge down the left
 * with its readout on the line beside it, then a thin burn bar and the fuel slot.
 *
 * <p>The readout sits on its own line above the slot row rather than centred on the gauge.
 * Centred looked tidier with an empty panel and then ran straight through the fuel slot once
 * the numbers got to six digits.
 */
public class FuelGeneratorScreen extends ThemedContainerScreen<FuelGeneratorMenu> {

    private static final int GAUGE_X = 8;
    private static final int GAUGE_W = 20;
    private static final int CONTENT_TOP_GAP = 6;
    private static final int READOUT_GAP = 8;

    /** Thin vertical burn bar, furnace-flame sized, immediately left of the fuel slot. */
    private static final int BURN_W = 6;
    private static final int BURN_H = 16;
    private static final int BURN_SLOT_GAP = 12;

    private ItemStack headerIcon = ItemStack.EMPTY;
    private EnergyGauge gauge;

    public FuelGeneratorScreen(FuelGeneratorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, GuiLayout.STANDARD_PANEL_WIDTH, FuelGeneratorMenu.PANEL_HEIGHT);
        this.inventoryLabelX = GuiLayout.playerInventoryLabelX(GuiLayout.STANDARD_PANEL_WIDTH);
        this.inventoryLabelY = GuiLayout.playerInventoryLabelY(FuelGeneratorMenu.INV_SLOT_Y);
        setHelpTooltip(Component.translatable("gui.bobbypipes.help.fuel_generator"));
    }

    @Override
    protected UiTheme uiTheme() {
        return PipeThemes.POWER_JUNCTION;
    }

    private int contentTop() {
        return topPos + PipeThemes.POWER_JUNCTION.headerHeight() + CONTENT_TOP_GAP;
    }

    private int gaugeHeight() {
        // Down to just above the player inventory: charge is the headline number here, so the
        // gauge should be the tallest thing on the panel.
        return (topPos + FuelGeneratorMenu.INV_SLOT_Y - 12) - contentTop();
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = PipeThemes.POWER_JUNCTION.titlePadY();
        this.gauge = new EnergyGauge(leftPos + GAUGE_X, contentTop(), GAUGE_W, gaugeHeight());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        PipeGui.drawPanelAndMenuSlots(
                graphics, PipeThemes.POWER_JUNCTION, leftPos, topPos, imageWidth, imageHeight,
                menu.slots);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ScreenHeader.draw(graphics, font, PipeThemes.POWER_JUNCTION, title, headerIcon,
                PanelStyle.LABEL);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (gauge == null) {
            return;
        }
        gauge.draw(graphics, PipeThemes.POWER_JUNCTION, menu.energy(), menu.capacity());
        drawReadout(graphics);
        GeneratorMeters.drawBurnGauge(graphics, burnX(), burnY(), BURN_W, BURN_H,
                menu.burnTicks(), menu.burnTicksTotal());
    }

    /**
     * Charge figure beside the gauge, on the line above the slot row.
     *
     * <p>Truncated rather than allowed to run under the burn bar and slot, so an unusually
     * wide capacity cannot break the layout.
     */
    private void drawReadout(GuiGraphicsExtractor graphics) {
        int x = gauge.right() + READOUT_GAP;
        int available = (leftPos + imageWidth - 8) - x;
        Component text = readout();
        if (font.width(text) > available) {
            graphics.text(font, font.plainSubstrByWidth(text.getString(), available),
                    x, contentTop(), PanelStyle.LABEL, false);
            return;
        }
        graphics.text(font, text, x, contentTop(), PanelStyle.LABEL, false);
    }

    private Component readout() {
        return Component.translatable("gui.bobbypipes.generator.amount",
                EnergyGauge.format(menu.energy()), EnergyGauge.format(menu.capacity()));
    }

    private int burnX() {
        return leftPos + FuelGeneratorMenu.FUEL_SLOT_X - BURN_SLOT_GAP;
    }

    /** Vertically centred on the 16px item area inside the slot. */
    private int burnY() {
        return topPos + FuelGeneratorMenu.FUEL_SLOT_Y;
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        if (gauge != null && gauge.isMouseOver(mouseX, mouseY)) {
            graphics.setTooltipForNextFrame(font, List.of(readout()),
                    Optional.empty(), ItemStack.EMPTY, mouseX, mouseY);
            return;
        }
        int bx = burnX();
        int by = burnY();
        if (mouseX >= bx && mouseX < bx + BURN_W && mouseY >= by && mouseY < by + BURN_H) {
            graphics.setTooltipForNextFrame(font,
                    List.of(Component.translatable("gui.bobbypipes.fuel_generator.remaining",
                            Mth.ceil(menu.burnTicks() / 20.0))),
                    Optional.empty(), ItemStack.EMPTY, mouseX, mouseY);
        }
    }
}
