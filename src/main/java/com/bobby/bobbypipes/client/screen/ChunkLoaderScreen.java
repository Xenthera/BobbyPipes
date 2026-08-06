package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.block.entity.ChunkLoaderBlockEntity;
import com.bobby.bobbypipes.menu.ChunkLoaderMenu;
import com.bobby.bobbypipes.network.payload.SetChunkLoaderSettingsPayload;
import com.bobby.bobbycore.client.gui.ThemedContainerScreen;
import com.bobby.bobbycore.client.gui.draw.ScreenHeader;
import com.bobby.bobbycore.client.gui.font.BobbyFonts;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import com.bobby.bobbycore.client.gui.widget.UiButton;
import com.bobby.bobbycore.client.gui.widget.UiCheckbox;
import com.bobby.bobbycore.client.gui.widget.UiTextBox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

public class ChunkLoaderScreen extends ThemedContainerScreen<ChunkLoaderMenu> {

    private static final int SIDE_PAD = 8;
    private static final int FIELD_H = 16;
    private static final int BUTTON_W = 40;
    private static final int CONTENT_TOP = 6;
    private static final int ROW_GAP = 6;
    private static final int CONTENT_BOTTOM = 8;

    private UiTextBox radiusBox;
    private UiCheckbox activeBox;
    private UiCheckbox debugBox;
    private Component status = Component.empty();
    private int statusColor = PanelStyle.LABEL;
    private ItemStack headerIcon = ItemStack.EMPTY;

    public ChunkLoaderScreen(ChunkLoaderMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, GuiLayout.STANDARD_PANEL_WIDTH, panelHeight());
        this.inventoryLabelY = 1000;
        setHelpTooltip(Component.translatable("gui.bobbypipes.help.chunk_loader"));
    }

    @Override
    protected UiTheme uiTheme() {
        return PipeThemes.CHUNK_LOADER;
    }

    private static int panelHeight() {
        return PipeThemes.CHUNK_LOADER.headerHeight()
                + CONTENT_TOP
                + FIELD_H
                + ROW_GAP
                + UiCheckbox.TRACK_H
                + ROW_GAP
                + UiCheckbox.TRACK_H
                + ROW_GAP
                + 10
                + CONTENT_BOTTOM;
    }

    private int radiusY() {
        return PipeThemes.CHUNK_LOADER.headerHeight() + CONTENT_TOP;
    }

    private int activeY() {
        return radiusY() + FIELD_H + ROW_GAP;
    }

    private int debugY() {
        return activeY() + UiCheckbox.TRACK_H + ROW_GAP;
    }

    private int footerY() {
        return debugY() + UiCheckbox.TRACK_H + ROW_GAP;
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = PipeThemes.CHUNK_LOADER.titlePadY();
        int fieldW = imageWidth - SIDE_PAD * 2 - BUTTON_W - 4;
        radiusBox = UiTextBox.builder(font, Component.translatable("gui.bobbypipes.chunk_loader.radius"))
                .bounds(leftPos + SIDE_PAD, topPos + radiusY(), fieldW, FIELD_H)
                .theme(PipeThemes.CHUNK_LOADER)
                .maxLength(2)
                .value(Integer.toString(menu.radius()))
                .build();
        addRenderableWidget(radiusBox);
        addRenderableWidget(UiButton.builder(
                        Component.translatable("gui.bobbypipes.chunk_loader.apply"),
                        b -> apply())
                .bounds(leftPos + SIDE_PAD + fieldW + 4, topPos + radiusY() - 1, BUTTON_W, FIELD_H + 2)
                .theme(PipeThemes.CHUNK_LOADER)
                .build());
        activeBox = UiCheckbox.builder(
                        Component.translatable("gui.bobbypipes.chunk_loader.active"),
                        selected -> send(currentRadius(), selected,
                                debugBox != null ? debugBox.selected() : menu.debugOutline()))
                .pos(leftPos + SIDE_PAD, topPos + activeY())
                .selected(menu.active())
                .theme(PipeThemes.CHUNK_LOADER)
                .build();
        addRenderableWidget(activeBox);
        debugBox = UiCheckbox.builder(
                        Component.translatable("gui.bobbypipes.chunk_loader.debug"),
                        selected -> send(currentRadius(),
                                activeBox != null ? activeBox.selected() : menu.active(), selected))
                .pos(leftPos + SIDE_PAD, topPos + debugY())
                .selected(menu.debugOutline())
                .theme(PipeThemes.CHUNK_LOADER)
                .build();
        addRenderableWidget(debugBox);
        setInitialFocus(radiusBox);
    }

    private int currentRadius() {
        if (radiusBox == null) {
            return menu.radius();
        }
        try {
            int value = Integer.parseInt(radiusBox.getValue().trim());
            if (value >= ChunkLoaderBlockEntity.MIN_RADIUS && value <= ChunkLoaderBlockEntity.MAX_RADIUS) {
                return value;
            }
        } catch (NumberFormatException ignored) {
        }
        return menu.radius();
    }

    private void apply() {
        String raw = radiusBox.getValue().trim();
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            status = Component.translatable("gui.bobbypipes.chunk_loader.invalid_radius");
            statusColor = PanelStyle.MARKER_RED;
            return;
        }
        if (value < ChunkLoaderBlockEntity.MIN_RADIUS || value > ChunkLoaderBlockEntity.MAX_RADIUS) {
            status = Component.translatable("gui.bobbypipes.chunk_loader.invalid_radius");
            statusColor = PanelStyle.MARKER_RED;
            return;
        }
        status = Component.empty();
        send(value, activeBox != null && activeBox.selected(),
                debugBox != null && debugBox.selected());
    }

    private void send(int radius, boolean active, boolean debug) {
        menu.setLocal(radius, active, debug);
        ClientPacketDistributor.sendToServer(
                new SetChunkLoaderSettingsPayload(menu.pos(), radius, active, debug));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        PipeGui.drawPanelAndMenuSlots(
                graphics, PipeThemes.CHUNK_LOADER, leftPos, topPos, imageWidth, imageHeight, menu.slots);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ScreenHeader.draw(graphics, font, PipeThemes.CHUNK_LOADER, title, headerIcon, PanelStyle.LABEL);
        if (!status.getString().isEmpty()) {
            graphics.text(font, BobbyFonts.apply(status), SIDE_PAD, footerY(), statusColor, false);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            onClose();
            return true;
        }
        if (radiusBox != null && radiusBox.isFocused() && event.isConfirmation()) {
            apply();
            return true;
        }
        if (radiusBox != null && radiusBox.keyPressed(event)) {
            return true;
        }
        if (radiusBox != null && radiusBox.canConsumeInput()) {
            return true;
        }
        return super.keyPressed(event);
    }
}
