package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.menu.SatellitePipeMenu;
import com.bobby.bobbypipes.network.payload.SetSatelliteNamePayload;
import com.bobby.bobbypipes.pipes.SatelliteNamingResult;
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

public class SatellitePipeScreen extends ThemedContainerScreen<SatellitePipeMenu> {

    private static final int SIDE_PAD = 8;
    private static final int FIELD_H = 16;
    private static final int BUTTON_W = 40;
    private static final int CONTENT_TOP = 6;
    private static final int FOOTER_GAP = 3;
    private static final int FOOTER_H = 10;
    private static final int CONTENT_BOTTOM = 6;

    private UiTextBox nameBox;
    private Component status = Component.empty();
    private int statusColor = PanelStyle.LABEL;
    private ItemStack headerIcon = ItemStack.EMPTY;

    public SatellitePipeScreen(SatellitePipeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, GuiLayout.STANDARD_PANEL_WIDTH, panelHeight());
        this.inventoryLabelY = 1000;
        setHelpTooltip(Component.translatable("gui.bobbypipes.help.satellite_pipe"));
    }

    @Override
    protected UiTheme uiTheme() {
        return PipeThemes.SATELLITE;
    }

    private static int panelHeight() {
        return PipeThemes.SATELLITE.headerHeight()
                + CONTENT_TOP
                + FIELD_H
                + FOOTER_GAP
                + FOOTER_H
                + CONTENT_BOTTOM;
    }

    private int fieldY() {
        return PipeThemes.SATELLITE.headerHeight() + CONTENT_TOP;
    }

    private int footerY() {
        return fieldY() + FIELD_H + FOOTER_GAP;
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = PipeThemes.SATELLITE.titlePadY();
        int fieldW = imageWidth - SIDE_PAD * 2 - BUTTON_W - 4;
        nameBox = UiTextBox.builder(font, Component.translatable("gui.bobbypipes.satellite.name"))
                .bounds(leftPos + SIDE_PAD, topPos + fieldY(), fieldW, FIELD_H)
                .theme(PipeThemes.SATELLITE)
                .maxLength(32)
                .value(menu.satelliteName())
                .build();
        addRenderableWidget(nameBox);
        addRenderableWidget(UiButton.builder(
                        Component.translatable("gui.bobbypipes.satellite.save"),
                        b -> ClientPacketDistributor.sendToServer(
                                new SetSatelliteNamePayload(menu.pos(), nameBox.getValue())))
                .bounds(leftPos + SIDE_PAD + fieldW + 4, topPos + fieldY() - 1, BUTTON_W, FIELD_H + 2)
                .theme(PipeThemes.SATELLITE)
                .build());
        setInitialFocus(nameBox);
    }

    public void onNamingResult(SatelliteNamingResult result, String name) {
        menu.setSatelliteNameLocal(name);
        if (nameBox != null && result == SatelliteNamingResult.SUCCESS) {
            nameBox.setValue(name);
        }
        // Success is visible via the persistent "Current ID" footer; keep the footer for errors.
        if (result == SatelliteNamingResult.SUCCESS) {
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
                graphics, PipeThemes.SATELLITE, leftPos, topPos, imageWidth, imageHeight, menu.slots);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ScreenHeader.draw(graphics, font, PipeThemes.SATELLITE, title, headerIcon, PanelStyle.LABEL);
        // Save feedback wins the footer briefly; otherwise show the active id when set.
        if (!status.getString().isEmpty()) {
            graphics.text(font, BobbyFonts.apply(status), SIDE_PAD, footerY(), statusColor, false);
            return;
        }
        String name = menu.satelliteName();
        if (name != null && !name.isBlank()) {
            Component current = BobbyFonts.apply(
                    Component.translatable("gui.bobbypipes.satellite.current_id", name));
            graphics.text(font, current, SIDE_PAD, footerY(), PanelStyle.MARKER_GREEN, false);
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
