package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.menu.ProviderPipeMenu;
import com.bobby.bobbypipes.network.payload.SetProviderSettingsPayload;
import com.bobby.bobbypipes.pipes.ProviderLeaveMode;
import com.bobby.bobbypipes.pipes.ProviderSettings;
import com.bobby.bobbycore.client.gui.ThemedContainerScreen;
import com.bobby.bobbycore.client.gui.draw.ScreenHeader;
import com.bobby.bobbycore.client.gui.font.BobbyFonts;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import com.bobby.bobbycore.client.gui.widget.UiButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Provider config: 9-slot whitelist/blacklist filter plus leave-stack extraction mode.
 */
public class ProviderPipeScreen extends ThemedContainerScreen<ProviderPipeMenu> {

    private static final int HOVER_TINT = 0x40_FF_FF_FF;
    private static final int SIDE_PAD = 8;
    private static final int CONTROL_GAP = 6;

    private ItemStack headerIcon = ItemStack.EMPTY;
    private FilterModeButton filterModeButton;
    private UiButton leaveButton;

    public ProviderPipeScreen(ProviderPipeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, GuiLayout.STANDARD_PANEL_WIDTH, ProviderPipeMenu.PANEL_HEIGHT);
        this.inventoryLabelX = GuiLayout.playerInventoryLabelX(GuiLayout.STANDARD_PANEL_WIDTH);
        this.inventoryLabelY = GuiLayout.playerInventoryLabelY(ProviderPipeMenu.INV_SLOT_Y);
        setHelpTooltip(Component.translatable("gui.bobbypipes.help.provider_pipe"));
    }

    @Override
    protected UiTheme uiTheme() {
        return PipeThemes.PROVIDER;
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = PipeThemes.PROVIDER.titlePadY();

        int controlsY = topPos + ProviderPipeMenu.CONTROLS_Y;
        filterModeButton = FilterModeButton.create(
                leftPos + SIDE_PAD,
                controlsY,
                menu.settings().include(),
                whitelist -> push(menu.settings().withInclude(whitelist)),
                PipeThemes.PROVIDER);
        addRenderableWidget(filterModeButton);

        int leaveX = filterModeButton.getX() + FilterModeButton.SIZE + CONTROL_GAP;
        int leaveW = Math.max(72, leftPos + imageWidth - SIDE_PAD - leaveX);
        leaveButton = UiButton.builder(leaveLabel(menu.settings().leaveMode()), button -> {
                    ProviderLeaveMode next = menu.settings().leaveMode().next();
                    push(menu.settings().withLeaveMode(next));
                    button.setMessage(BobbyFonts.apply(leaveLabel(next)));
                    button.setTooltip(Tooltip.create(leaveTip(next)));
                })
                .bounds(leaveX, controlsY, leaveW, FilterModeButton.SIZE)
                .tooltip(Tooltip.create(leaveTip(menu.settings().leaveMode())))
                .theme(PipeThemes.PROVIDER)
                .build();
        addRenderableWidget(leaveButton);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ScreenHeader.draw(graphics, font, PipeThemes.PROVIDER, title, headerIcon, PanelStyle.LABEL);
        graphics.text(font, BobbyFonts.apply(playerInventoryTitle), inventoryLabelX, inventoryLabelY,
                PanelStyle.LABEL, false);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        PipeGui.drawPanelAndMenuSlots(
                graphics, PipeThemes.PROVIDER, leftPos, topPos, imageWidth, imageHeight,
                GuiLayout.playerInventoryBandY(ProviderPipeMenu.INV_SLOT_Y), menu.slots);
        PipeGui.drawSlotRow(
                graphics,
                PipeThemes.PROVIDER,
                leftPos + CraftingPipeLayout.slotX(0),
                topPos + ProviderPipeMenu.FILTER_SLOT_Y,
                ProviderSettings.SLOT_COUNT,
                CraftingPipeLayout.SLOT);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractContents(graphics, mouseX, mouseY, partialTick);
        ProviderSettings settings = menu.settings();
        for (int i = 0; i < ProviderSettings.SLOT_COUNT; i++) {
            int x = leftPos + CraftingPipeLayout.slotX(i);
            int y = topPos + ProviderPipeMenu.FILTER_SLOT_Y;
            boolean hovered = mouseX >= x && mouseX < x + CraftingPipeLayout.SLOT
                    && mouseY >= y && mouseY < y + CraftingPipeLayout.SLOT;
            if (hovered) {
                graphics.fill(x + 1, y + 1, x + 17, y + 17, HOVER_TINT);
            }
            ItemStack stack = settings.slot(i);
            if (!stack.isEmpty()) {
                int ix = x + 1;
                int iy = y + 1;
                graphics.item(stack, ix, iy);
                graphics.itemDecorations(font, stack, ix, iy);
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int slot = slotAt(event.x(), event.y());
        if (slot < 0) {
            return super.mouseClicked(event, doubleClick);
        }
        ItemStack carried = minecraft.player != null
                ? minecraft.player.containerMenu.getCarried()
                : ItemStack.EMPTY;
        if (carried.isEmpty()) {
            if (event.button() == 1) {
                push(menu.settings().withSlot(slot, ItemStack.EMPTY));
            }
            return true;
        }
        push(menu.settings().withSlot(slot, carried.copyWithCount(1)));
        return true;
    }

    private int slotAt(double mouseX, double mouseY) {
        for (int i = 0; i < ProviderSettings.SLOT_COUNT; i++) {
            int x = leftPos + CraftingPipeLayout.slotX(i);
            int y = topPos + ProviderPipeMenu.FILTER_SLOT_Y;
            if (mouseX >= x && mouseX < x + CraftingPipeLayout.SLOT
                    && mouseY >= y && mouseY < y + CraftingPipeLayout.SLOT) {
                return i;
            }
        }
        return -1;
    }

    private void push(ProviderSettings settings) {
        menu.setSettingsLocal(settings);
        if (filterModeButton != null) {
            filterModeButton.setWhitelist(settings.include());
        }
        if (leaveButton != null) {
            leaveButton.setMessage(BobbyFonts.apply(leaveLabel(settings.leaveMode())));
            leaveButton.setTooltip(Tooltip.create(leaveTip(settings.leaveMode())));
        }
        ClientPacketDistributor.sendToServer(new SetProviderSettingsPayload(menu.pos(), settings));
    }

    private static Component leaveLabel(ProviderLeaveMode mode) {
        return Component.translatable("gui.bobbypipes.provider.leave." + mode.getSerializedName());
    }

    private static Component leaveTip(ProviderLeaveMode mode) {
        return Component.translatable("gui.bobbypipes.provider.leave." + mode.getSerializedName() + ".tip");
    }
}
