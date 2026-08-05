package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.menu.BasicPipeMenu;
import com.bobby.bobbypipes.network.payload.SetDefaultRoutePayload;
import com.bobby.bobbycore.client.gui.ThemedContainerScreen;
import com.bobby.bobbycore.client.gui.draw.ScreenHeader;
import com.bobby.bobbycore.client.gui.font.BobbyFonts;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import com.bobby.bobbycore.client.gui.widget.UiCheckbox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public class BasicPipeScreen extends ThemedContainerScreen<BasicPipeMenu> {

    private static final int SIDE_PAD = 8;
    private static final int CONTENT_TOP = 6;
    private static final int CONTENT_BOTTOM = 8;

    private ItemStack headerIcon = ItemStack.EMPTY;

    public BasicPipeScreen(BasicPipeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, panelWidth(), panelHeight());
        this.inventoryLabelY = 1000;
        setHelpTooltip(Component.translatable("gui.bobbypipes.help.basic_pipe"));
    }

    @Override
    protected UiTheme uiTheme() {
        return PipeThemes.BASIC;
    }

    private static int panelWidth() {
        var font = Minecraft.getInstance().font;
        Component label = BobbyFonts.apply(Component.translatable("gui.bobbypipes.basic.default_route"));
        int contentW = UiCheckbox.TRACK_W + UiCheckbox.GAP + font.width(label);
        return Math.max(120, SIDE_PAD + contentW + SIDE_PAD);
    }

    private static int panelHeight() {
        return PipeThemes.BASIC.headerHeight() + CONTENT_TOP + UiCheckbox.TRACK_H + CONTENT_BOTTOM;
    }

    private int contentY() {
        return PipeThemes.BASIC.headerHeight() + CONTENT_TOP;
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = PipeThemes.BASIC.titlePadY();
        addRenderableWidget(UiCheckbox.builder(
                        Component.translatable("gui.bobbypipes.basic.default_route"),
                        selected -> {
                            menu.setDefaultRouteLocal(selected);
                            ClientPacketDistributor.sendToServer(
                                    new SetDefaultRoutePayload(menu.pos(), selected));
                        })
                .pos(leftPos + SIDE_PAD, topPos + contentY())
                .selected(menu.isDefaultRoute())
                .theme(PipeThemes.BASIC)
                .build());
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ScreenHeader.draw(graphics, font, PipeThemes.BASIC, title, headerIcon, PanelStyle.LABEL);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        PipeGui.drawPanelAndMenuSlots(graphics, PipeThemes.BASIC, leftPos, topPos, imageWidth, imageHeight, menu.slots);
    }
}
