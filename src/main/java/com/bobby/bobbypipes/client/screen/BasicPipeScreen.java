package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.menu.BasicPipeMenu;
import com.bobby.bobbypipes.network.payload.SetDefaultRoutePayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public class BasicPipeScreen extends AbstractContainerScreen<BasicPipeMenu> {

    public BasicPipeScreen(BasicPipeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 80);
        this.inventoryLabelY = 1000;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Checkbox.builder(
                        Component.translatable("gui.bobbypipes.basic.default_route"), font)
                .pos(leftPos + 20, topPos + 36)
                .selected(menu.isDefaultRoute())
                .onValueChange((checkbox, selected) -> {
                    menu.setDefaultRouteLocal(selected);
                    ClientPacketDistributor.sendToServer(
                            new SetDefaultRoutePayload(menu.pos(), selected));
                })
                .maxWidth(140)
                .build());
    }

    /** White, not vanilla's dark grey: this panel is not a stone-coloured vanilla one. */
    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, title, titleLabelX, titleLabelY, PanelStyle.LABEL, false);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                ModGuiTextures.BASIC_PIPE,
                leftPos,
                topPos,
                0.0F,
                0.0F,
                imageWidth,
                imageHeight,
                256,
                256);
    }
}
