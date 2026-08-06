package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.menu.LinkPipeMenu;
import com.bobby.bobbypipes.network.LinkPipeRegistry;
import com.bobby.bobbypipes.network.payload.SetLinkChannelPayload;
import com.bobby.bobbypipes.pipes.LinkChannelResult;
import com.bobby.bobbycore.client.gui.ThemedContainerScreen;
import com.bobby.bobbycore.client.gui.draw.ScreenHeader;
import com.bobby.bobbycore.client.gui.font.BobbyFonts;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import com.bobby.bobbycore.client.gui.widget.UiButton;
import com.bobby.bobbycore.client.gui.widget.UiTextBox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public class LinkPipeScreen extends ThemedContainerScreen<LinkPipeMenu> {

    private static final int SIDE_PAD = 8;
    private static final int FIELD_H = 16;
    private static final int BUTTON_W = 40;
    private static final int BUTTON_GAP = 4;
    private static final int CONTENT_TOP = 6;
    private static final int FOOTER_GAP = 3;
    private static final int FOOTER_H = 10;
    private static final int CONTENT_BOTTOM = 6;

    private UiTextBox channelBox;
    private Component status = Component.empty();
    private int statusColor = PanelStyle.LABEL;
    private ItemStack headerIcon = ItemStack.EMPTY;

    public LinkPipeScreen(LinkPipeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, GuiLayout.STANDARD_PANEL_WIDTH, panelHeight());
        this.inventoryLabelY = 1000;
        setHelpTooltip(Component.translatable("gui.bobbypipes.help.link_pipe"));
    }

    @Override
    protected UiTheme uiTheme() {
        return PipeThemes.LINK;
    }

    private static int panelHeight() {
        return PipeThemes.LINK.headerHeight()
                + CONTENT_TOP
                + FIELD_H
                + FOOTER_GAP
                + FOOTER_H
                + CONTENT_BOTTOM;
    }

    private int fieldY() {
        return PipeThemes.LINK.headerHeight() + CONTENT_TOP;
    }

    private int footerY() {
        return fieldY() + FIELD_H + FOOTER_GAP;
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = PipeThemes.LINK.titlePadY();
        int buttonsWidth = BUTTON_W * 2 + BUTTON_GAP;
        int fieldW = imageWidth - SIDE_PAD * 2 - buttonsWidth - BUTTON_GAP;
        channelBox = UiTextBox.builder(font, Component.translatable("gui.bobbypipes.link.channel"))
                .bounds(leftPos + SIDE_PAD, topPos + fieldY(), fieldW, FIELD_H)
                .theme(PipeThemes.LINK)
                .maxLength(8)
                .value(menu.channel() > 0 ? Integer.toString(menu.channel()) : "")
                .build();
        addRenderableWidget(channelBox);
        int saveX = leftPos + SIDE_PAD + fieldW + BUTTON_GAP;
        addRenderableWidget(UiButton.builder(
                        Component.translatable("gui.bobbypipes.link.save"),
                        b -> submit())
                .bounds(saveX, topPos + fieldY() - 1, BUTTON_W, FIELD_H + 2)
                .theme(PipeThemes.LINK)
                .build());
        addRenderableWidget(UiButton.builder(
                        Component.translatable("gui.bobbypipes.link.clear"),
                        b -> clearChannel())
                .bounds(saveX + BUTTON_W + BUTTON_GAP, topPos + fieldY() - 1, BUTTON_W, FIELD_H + 2)
                .theme(PipeThemes.LINK)
                .build());
        setInitialFocus(channelBox);
    }

    private void clearChannel() {
        if (channelBox != null) {
            channelBox.setValue("");
        }
        ClientPacketDistributor.sendToServer(new SetLinkChannelPayload(menu.pos(), 0));
    }

    private void submit() {
        String raw = channelBox.getValue().trim();
        int value = 0;
        if (!raw.isEmpty()) {
            try {
                value = Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                status = Component.translatable(LinkChannelResult.INVALID_CHANNEL.langKey());
                statusColor = PanelStyle.MARKER_RED;
                return;
            }
        }
        if (value != 0 && (value < LinkPipeRegistry.MIN_CHANNEL || value > LinkPipeRegistry.MAX_CHANNEL)) {
            status = Component.translatable(LinkChannelResult.INVALID_CHANNEL.langKey());
            statusColor = PanelStyle.MARKER_RED;
            return;
        }
        ClientPacketDistributor.sendToServer(new SetLinkChannelPayload(menu.pos(), value));
    }

    public void onChannelResult(LinkChannelResult result, int channel, boolean paired, boolean live) {
        menu.setChannelLocal(channel, paired, live);
        if (channelBox != null && result == LinkChannelResult.SUCCESS) {
            channelBox.setValue(channel > 0 ? Integer.toString(channel) : "");
        }
        if (result == LinkChannelResult.SUCCESS) {
            status = Component.empty();
        } else {
            status = Component.translatable(result.langKey());
            statusColor = PanelStyle.MARKER_RED;
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        PipeGui.drawPanelAndMenuSlots(
                graphics, PipeThemes.LINK, leftPos, topPos, imageWidth, imageHeight, menu.slots);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ScreenHeader.draw(graphics, font, PipeThemes.LINK, title, headerIcon, PanelStyle.LABEL);
        if (!status.getString().isEmpty()) {
            graphics.text(font, BobbyFonts.apply(status), SIDE_PAD, footerY(), statusColor, false);
            return;
        }
        String footer;
        int footerColor = PanelStyle.LABEL;
        if (menu.live()) {
            footer = "gui.bobbypipes.link.paired";
        } else if (menu.paired()) {
            footer = "gui.bobbypipes.link.severed";
            footerColor = PanelStyle.MARKER_RED;
        } else if (menu.channel() > 0) {
            footer = "gui.bobbypipes.link.waiting";
        } else {
            footer = "gui.bobbypipes.link.unpaired";
        }
        graphics.text(font, BobbyFonts.apply(Component.translatable(footer, menu.channel())),
                SIDE_PAD, footerY(), footerColor, false);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            onClose();
            return true;
        }
        if (channelBox != null && channelBox.isFocused() && event.isConfirmation()) {
            submit();
            return true;
        }
        if (channelBox != null && channelBox.keyPressed(event)) {
            return true;
        }
        if (channelBox != null && channelBox.canConsumeInput()) {
            return true;
        }
        return super.keyPressed(event);
    }
}
