package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.menu.TwerkGeneratorMenu;
import com.bobby.bobbycore.client.gui.ThemedContainerScreen;
import com.bobby.bobbycore.client.gui.draw.ScreenHeader;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Twerk generator UI: the same tall charge gauge as the fuel generator, and nothing else,
 * because there is nothing else.
 */
public class TwerkGeneratorScreen extends ThemedContainerScreen<TwerkGeneratorMenu> {

    private static final int GAUGE_X = 12;
    private static final int GAUGE_W = 20;
    private static final int GAUGE_H = 58;
    private static final int GAUGE_TOP_GAP = 6;
    private static final int READOUT_GAP = 8;
    private static final int CONTENT_BOTTOM = 10;

    private ItemStack headerIcon = ItemStack.EMPTY;
    private EnergyGauge gauge;

    public TwerkGeneratorScreen(TwerkGeneratorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, GuiLayout.STANDARD_PANEL_WIDTH, panelHeight());
        this.inventoryLabelY = 1000;
        setHelpTooltip(Component.translatable("gui.bobbypipes.help.twerk_generator"));
    }

    @Override
    protected UiTheme uiTheme() {
        return PipeThemes.SATELLITE;
    }

    private static int panelHeight() {
        return PipeThemes.SATELLITE.headerHeight() + GAUGE_TOP_GAP + GAUGE_H + CONTENT_BOTTOM;
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = PipeThemes.SATELLITE.titlePadY();
        this.gauge = new EnergyGauge(leftPos + GAUGE_X,
                topPos + PipeThemes.SATELLITE.headerHeight() + GAUGE_TOP_GAP, GAUGE_W, GAUGE_H);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        PipeGui.drawPanelAndMenuSlots(
                graphics, PipeThemes.SATELLITE, leftPos, topPos, imageWidth, imageHeight,
                menu.slots);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ScreenHeader.draw(graphics, font, PipeThemes.SATELLITE, title, headerIcon,
                PanelStyle.LABEL);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (gauge == null) {
            return;
        }
        gauge.draw(graphics, PipeThemes.SATELLITE, menu.energy(), menu.capacity());

        // Readout back on the gauge's centre line now that nothing sits under it.
        graphics.text(font, readout(), gauge.right() + READOUT_GAP, gauge.centreY() - 4,
                PanelStyle.LABEL, false);
    }

    private Component readout() {
        return Component.translatable("gui.bobbypipes.generator.amount",
                EnergyGauge.format(menu.energy()), EnergyGauge.format(menu.capacity()));
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        if (gauge != null && gauge.isMouseOver(mouseX, mouseY)) {
            graphics.setTooltipForNextFrame(font, List.of(readout()),
                    Optional.empty(), ItemStack.EMPTY, mouseX, mouseY);
        }
    }
}
