package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.menu.SatellitePipeMenu;
import com.bobby.bobbypipes.network.payload.SetSatelliteNamePayload;
import com.bobby.bobbypipes.pipes.SatelliteNamingResult;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public class SatellitePipeScreen extends AbstractContainerScreen<SatellitePipeMenu> {

    private EditBox nameBox;
    private Component status = Component.empty();
    private int statusColor = PanelStyle.LABEL;

    public SatellitePipeScreen(SatellitePipeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 90);
        this.inventoryLabelY = 1000;
    }

    @Override
    protected void init() {
        super.init();
        nameBox = new EditBox(font, leftPos + 20, topPos + 36, 100, 16,
                Component.translatable("gui.bobbypipes.satellite.name"));
        nameBox.setMaxLength(32);
        nameBox.setValue(menu.satelliteName());
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.bobbypipes.satellite.save"),
                        b -> ClientPacketDistributor.sendToServer(
                                new SetSatelliteNamePayload(menu.pos(), nameBox.getValue())))
                .bounds(leftPos + 124, topPos + 35, 40, 18)
                .build());
        setInitialFocus(nameBox);
    }

    public void onNamingResult(SatelliteNamingResult result, String name) {
        menu.setSatelliteNameLocal(name);
        if (nameBox != null && result == SatelliteNamingResult.SUCCESS) {
            nameBox.setValue(name);
        }
        status = Component.translatable(result.langKey());
        statusColor = result == SatelliteNamingResult.SUCCESS
                ? PanelStyle.MARKER_GREEN
                : PanelStyle.MARKER_RED;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                ModGuiTextures.SATELLITE_PIPE,
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
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // Drawn here rather than via super, which hardcodes vanilla's dark grey.
        graphics.text(font, title, titleLabelX, titleLabelY, PanelStyle.LABEL, false);
        // Panel-relative: this method already runs inside a translate to leftPos/topPos,
        // so adding them again put the status clean off the panel.
        if (!status.getString().isEmpty()) {
            graphics.text(font, status, 20, 60, statusColor, false);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            onClose();
            return true;
        }
        if (nameBox != null && nameBox.keyPressed(event)) {
            return true;
        }
        if (nameBox != null && nameBox.canConsumeInput()) {
            return true;
        }
        return super.keyPressed(event);
    }
}
