package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbycore.client.gui.ThemedContainerScreen;
import com.bobby.bobbycore.client.gui.draw.ScreenHeader;
import com.bobby.bobbycore.client.gui.font.BobbyFonts;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import com.bobby.bobbycore.client.gui.widget.UiButton;
import com.bobby.bobbycore.client.gui.widget.UiTextBox;
import com.bobby.bobbypipes.client.ClientEnergyRequestGui;
import com.bobby.bobbypipes.menu.EnergyRequestMenu;
import com.bobby.bobbypipes.client.ClientRequestOptions;
import com.bobby.bobbypipes.network.payload.RequestEnergyPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Energy request pipe screen: no item grid, there is only one kind of energy. Just an
 * amount field, a request button, and how much the network can currently supply.
 */
public class EnergyRequestScreen extends ThemedContainerScreen<EnergyRequestMenu> {

    private static final int SIDE_PAD = 8;
    private static final int CONTENT_TOP = 6;
    private static final int ROW_GAP = 6;
    private static final int AMOUNT_W = 70;
    private static final int ROW_H = 18;
    private static final int CONTENT_BOTTOM = 8;
    private static final int PARTIAL_W = 22;
    private static final int PARTIAL_GAP = 3;

    private ItemStack headerIcon = ItemStack.EMPTY;
    private UiTextBox amount;
    private UiButton requestButton;
    private UiButton partialButton;

    public EnergyRequestScreen(EnergyRequestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, panelWidth(), panelHeight());
        this.inventoryLabelY = 1000;
        setHelpTooltip(Component.translatable("gui.bobbypipes.help.energy_request"));
    }

    @Override
    protected UiTheme uiTheme() {
        return PipeThemes.REQUEST;
    }

    private static int panelWidth() {
        var font = Minecraft.getInstance().font;
        Component available = BobbyFonts.apply(
                Component.translatable("gui.bobbypipes.energy_request.available", 999_999_999));
        int contentW = Math.max(SIDE_PAD + AMOUNT_W + 4 + 70, font.width(available));
        return Math.max(140, SIDE_PAD + contentW + SIDE_PAD);
    }

    private static int panelHeight() {
        return PipeThemes.REQUEST.headerHeight()
                + CONTENT_TOP + ROW_H + ROW_GAP + ROW_H + ROW_GAP + font(9) + CONTENT_BOTTOM;
    }

    private static int font(int lineHeight) {
        return lineHeight;
    }

    private int contentY() {
        return PipeThemes.REQUEST.headerHeight() + CONTENT_TOP;
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = PipeThemes.REQUEST.titlePadY();

        int row1 = topPos + contentY();
        amount = UiTextBox.builder(font, Component.translatable("gui.bobbypipes.energy_request.amount"))
                .bounds(leftPos + SIDE_PAD, row1, AMOUNT_W, ROW_H)
                .theme(PipeThemes.REQUEST)
                .maxLength(9)
                .filter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit))
                .value("1000")
                .build();
        addRenderableWidget(amount);

        int row2 = row1 + ROW_H + ROW_GAP;
        int partialX = leftPos + imageWidth - SIDE_PAD - PARTIAL_W;
        requestButton = UiButton.builder(
                        Component.translatable("gui.bobbypipes.energy_request.submit"),
                        button -> sendRequest())
                .bounds(leftPos + SIDE_PAD, row2,
                        imageWidth - SIDE_PAD * 2 - PARTIAL_W - PARTIAL_GAP, ROW_H)
                .build()
                .setTheme(PipeThemes.REQUEST);
        addRenderableWidget(requestButton);

        partialButton = UiButton.builder(partialLabel(), button -> {
                    ClientRequestOptions.toggleEnergyAllowPartial();
                    button.setMessage(BobbyFonts.apply(partialLabel()));
                    button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                            partialTooltip()));
                })
                .bounds(partialX, row2, PARTIAL_W, ROW_H)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(partialTooltip()))
                .build()
                .setTheme(PipeThemes.REQUEST);
        addRenderableWidget(partialButton);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        requestButton.active = parseAmount() > 0;
    }

    private void sendRequest() {
        int wanted = parseAmount();
        if (wanted <= 0) {
            return;
        }
        ClientPacketDistributor.sendToServer(new RequestEnergyPayload(
                menu.pos(), wanted, ClientRequestOptions.energyAllowPartial()));
    }

    /** Approximate sign while a short order may still ship, equals sign for exact-only. */
    private static Component partialLabel() {
        return BobbyFonts.literal(ClientRequestOptions.energyAllowPartial() ? "≈" : "=");
    }

    private static Component partialTooltip() {
        return Component.translatable(ClientRequestOptions.energyAllowPartial()
                ? "gui.bobbypipes.request.partial.on"
                : "gui.bobbypipes.request.partial.off");
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

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ScreenHeader.draw(graphics, font, PipeThemes.REQUEST, title, headerIcon, PanelStyle.LABEL);
        // Drawn in the same font panelWidth() measured it in, or a long figure would run
        // past the panel edge the layout reserved for it.
        Component available = BobbyFonts.apply(Component.translatable(
                "gui.bobbypipes.energy_request.available", ClientEnergyRequestGui.availableFe()));
        int y = contentY() + ROW_H + ROW_GAP + ROW_H + ROW_GAP;
        graphics.text(font, available, SIDE_PAD, y, PanelStyle.LABEL, false);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        PipeGui.drawPanelAndMenuSlots(
                graphics, PipeThemes.REQUEST, leftPos, topPos, imageWidth, imageHeight, menu.slots);
    }
}
