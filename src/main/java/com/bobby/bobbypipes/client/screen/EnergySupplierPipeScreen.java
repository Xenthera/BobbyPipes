package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbycore.client.gui.ThemedContainerScreen;
import com.bobby.bobbycore.client.gui.draw.ScreenHeader;
import com.bobby.bobbycore.client.gui.font.BobbyFonts;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import com.bobby.bobbycore.client.gui.widget.UiButton;
import com.bobby.bobbycore.client.gui.widget.UiTextBox;
import com.bobby.bobbypipes.menu.EnergySupplierPipeMenu;
import com.bobby.bobbypipes.network.payload.SetEnergySupplierTargetPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Energy supplier pipe screen: one target-FE field this pipe keeps its attached storage
 * filled to, and a button to commit it. No ghost item slots, there is only one kind of
 * energy to target.
 */
public class EnergySupplierPipeScreen extends ThemedContainerScreen<EnergySupplierPipeMenu> {

    private static final int SIDE_PAD = 8;
    private static final int CONTENT_TOP = 6;
    private static final int ROW_GAP = 6;
    private static final int TARGET_W = 70;
    private static final int ROW_H = 18;
    private static final int CONTENT_BOTTOM = 8;
    /** Gap between the target label and the field it names. */
    private static final int LABEL_GAP = 6;

    /**
     * The label, in the font it is actually drawn in.
     *
     * <p>Measuring the plain component while drawing the UI font (or the reverse) is what
     * put the field on top of the text: the two fonts are different widths, so the layout
     * reserved room for one and painted the other.
     */
    private static Component targetLabel() {
        return BobbyFonts.apply(Component.translatable("gui.bobbypipes.energy_supplier.target"));
    }

    private ItemStack headerIcon = ItemStack.EMPTY;
    private UiTextBox target;

    public EnergySupplierPipeScreen(EnergySupplierPipeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, panelWidth(), panelHeight());
        this.inventoryLabelY = 1000;
        setHelpTooltip(Component.translatable("gui.bobbypipes.help.energy_supplier_pipe"));
    }

    @Override
    protected UiTheme uiTheme() {
        return PipeThemes.SUPPLIER;
    }

    private static int panelWidth() {
        int contentW = Minecraft.getInstance().font.width(targetLabel()) + LABEL_GAP + TARGET_W;
        return Math.max(140, SIDE_PAD + contentW + SIDE_PAD);
    }

    private static int panelHeight() {
        return PipeThemes.SUPPLIER.headerHeight()
                + CONTENT_TOP + ROW_H + ROW_GAP + ROW_H + CONTENT_BOTTOM;
    }

    private int contentY() {
        return PipeThemes.SUPPLIER.headerHeight() + CONTENT_TOP;
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = PipeThemes.SUPPLIER.titlePadY();

        int labelW = font.width(targetLabel());
        int row1 = topPos + contentY();
        target = UiTextBox.builder(font, Component.translatable("gui.bobbypipes.energy_supplier.target"))
                .bounds(leftPos + SIDE_PAD + labelW + LABEL_GAP, row1, TARGET_W, ROW_H)
                .theme(PipeThemes.SUPPLIER)
                .maxLength(9)
                .filter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit))
                .value(Integer.toString(menu.targetFe()))
                .build();
        addRenderableWidget(target);

        int row2 = row1 + ROW_H + ROW_GAP;
        UiButton setButton = UiButton.builder(
                        Component.translatable("gui.bobbypipes.energy_supplier.set"),
                        button -> commitTarget())
                .bounds(leftPos + SIDE_PAD, row2, imageWidth - SIDE_PAD * 2, ROW_H)
                .build()
                .setTheme(PipeThemes.SUPPLIER);
        addRenderableWidget(setButton);
    }

    private void commitTarget() {
        int value = parseTarget();
        menu.setTargetFeLocal(value);
        ClientPacketDistributor.sendToServer(new SetEnergySupplierTargetPayload(menu.pos(), value));
    }

    private int parseTarget() {
        if (target == null) {
            return 0;
        }
        String text = target.getValue().trim();
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
        ScreenHeader.draw(graphics, font, PipeThemes.SUPPLIER, title, headerIcon, PanelStyle.LABEL);
        graphics.text(font, targetLabel(), SIDE_PAD, contentY() + 5, PanelStyle.LABEL, false);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        PipeGui.drawPanelAndMenuSlots(
                graphics, PipeThemes.SUPPLIER, leftPos, topPos, imageWidth, imageHeight, menu.slots);
    }
}
