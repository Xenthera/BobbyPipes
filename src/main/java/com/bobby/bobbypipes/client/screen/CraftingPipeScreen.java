package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.craft.CraftPattern;
import com.bobby.bobbypipes.menu.CraftingPipeMenu;
import com.bobby.bobbypipes.network.payload.ImportCraftPatternPayload;
import com.bobby.bobbypipes.network.payload.RequestSatelliteListPayload;
import com.bobby.bobbypipes.network.payload.SetCraftPatternPayload;
import com.bobby.bobbycore.client.gui.ThemedContainerScreen;
import com.bobby.bobbycore.client.gui.draw.ScreenHeader;
import com.bobby.bobbycore.client.gui.draw.UiDraw;
import com.bobby.bobbycore.client.gui.font.BobbyFonts;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import com.bobby.bobbycore.client.gui.widget.UiButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.List;
import java.util.Optional;

/**
 * LP-style crafting pipe: row of 9 ingredient ghosts, result, import, one satellite.
 */
public class CraftingPipeScreen extends ThemedContainerScreen<CraftingPipeMenu> {

    /**
     * Button row, sat directly under the result slot.
     *
     * <p>Shorter than the default 20 so the row clears the result slot, which ends at
     * {@code RESULT_Y + SLOT}, without running into the player inventory below it.
     */
    private static final int BUTTON_Y = CraftingPipeLayout.RESULT_Y + CraftingPipeLayout.SLOT + 1;
    private static final int BUTTON_HEIGHT = 14;
    private static final int INV_SLOT_Y = 100 + GuiLayout.CONTENT_TOP_PAD;

    private List<String> satelliteNames = List.of();
    private int listIndex;
    private boolean listOpen;
    private ItemStack headerIcon = ItemStack.EMPTY;

    public CraftingPipeScreen(CraftingPipeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, GuiLayout.STANDARD_PANEL_WIDTH, 181 + PipeGui.CONTENT_PAD);
        this.inventoryLabelX = GuiLayout.playerInventoryLabelX(GuiLayout.STANDARD_PANEL_WIDTH);
        this.inventoryLabelY = GuiLayout.playerInventoryLabelY(INV_SLOT_Y);
        setHelpTooltip(Component.translatable("gui.bobbypipes.help.crafting_pipe"));
    }

    @Override
    protected UiTheme uiTheme() {
        return PipeThemes.CRAFTING;
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = PipeThemes.CRAFTING.titlePadY();
        addRenderableWidget(UiButton.builder(
                        Component.translatable("gui.bobbypipes.crafting.import"),
                        b -> ClientPacketDistributor.sendToServer(new ImportCraftPatternPayload(menu.pos())))
                .bounds(leftPos + 8, topPos + BUTTON_Y, 50, BUTTON_HEIGHT)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.bobbypipes.crafting.import.tip")))
                .build());
        addRenderableWidget(UiButton.builder(
                        Component.translatable("gui.bobbypipes.crafting.satellite_select"),
                        b -> {
                            listOpen = !listOpen;
                            if (listOpen) {
                                ClientPacketDistributor.sendToServer(
                                        new RequestSatelliteListPayload(menu.pos()));
                            }
                        })
                .bounds(leftPos + 62, topPos + BUTTON_Y, 54, BUTTON_HEIGHT)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.bobbypipes.crafting.satellite_select.tip")))
                .build());
        addRenderableWidget(UiButton.builder(
                        Component.translatable("gui.bobbypipes.crafting.satellite_unset"),
                        b -> {
                            listOpen = false;
                            push(menu.pattern().withSatellite(""));
                        })
                .bounds(leftPos + 120, topPos + BUTTON_Y, 48, BUTTON_HEIGHT)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.bobbypipes.crafting.satellite_unset.tip")))
                .build());
    }

    public void onSatelliteList(BlockPos crafterPos, List<String> names) {
        if (!crafterPos.equals(menu.pos())) {
            return;
        }
        this.satelliteNames = List.copyOf(names);
        this.listIndex = 0;
        this.listOpen = true;
    }

    private void push(CraftPattern pattern) {
        menu.setPatternLocal(pattern);
        ClientPacketDistributor.sendToServer(new SetCraftPatternPayload(
                menu.pos(), SetCraftPatternPayload.Target.PIPE, pattern));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        PipeGui.drawPanelAndMenuSlots(
                graphics, PipeThemes.CRAFTING, leftPos, topPos, imageWidth, imageHeight,
                GuiLayout.playerInventoryBandY(INV_SLOT_Y), menu.slots);
        PipeGui.drawSlotRow(
                graphics,
                PipeThemes.CRAFTING,
                leftPos + CraftingPipeLayout.slotX(0),
                topPos + CraftingPipeLayout.slotY(),
                9,
                CraftingPipeLayout.SLOT);
        PipeGui.drawSlotFrame(graphics, PipeThemes.CRAFTING,
                leftPos + CraftingPipeLayout.RESULT_X, topPos + CraftingPipeLayout.RESULT_Y);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // Drawn here rather than via super, which hardcodes vanilla's dark grey.
        ScreenHeader.draw(graphics, font, PipeThemes.CRAFTING, title, headerIcon, PanelStyle.LABEL);
        graphics.text(font, BobbyFonts.apply(playerInventoryTitle), inventoryLabelX, inventoryLabelY,
                PanelStyle.LABEL, false);
        String sat = menu.pattern().satellite();
        Component line = BobbyFonts.apply(sat.isBlank()
                ? Component.translatable("gui.bobbypipes.crafting.satellite_none")
                : Component.translatable("gui.bobbypipes.crafting.satellite_current", sat));
        // Green only once this is actually routing somewhere. No satellite reads the same
        // as a satellite that has gone missing, because neither will deliver anywhere.
        boolean routing = !sat.isBlank()
                && (satelliteNames.isEmpty() || satelliteNames.contains(sat));
        // extractLabels is already translated to leftPos/topPos.
        graphics.text(font, line, 8, 46 + PipeGui.CONTENT_PAD, routing ? PanelStyle.MARKER_GREEN : PanelStyle.MARKER_RED, false);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractContents(graphics, mouseX, mouseY, partialTick);
        CraftPattern pattern = menu.pattern();
        if (pattern.hasInputs() || !pattern.primaryOutput().isEmpty()) {
            CraftingPipeLayout.drawGhosts(graphics, font, leftPos, topPos, pattern);
        }
        // Satellite column stays highlighted whenever a satellite is selected.
        if (pattern.hasSatellite()) {
            for (int slot = CraftPattern.SATELLITE_SLOT_START; slot < CraftPattern.SATELLITE_SLOT_END; slot++) {
                int x = leftPos + CraftingPipeLayout.slotX(slot);
                int y = topPos + CraftingPipeLayout.slotY();
                UiDraw.border(graphics, x, y, CraftingPipeLayout.SLOT, CraftingPipeLayout.SLOT,
                        PipeThemes.CRAFTING.accent(), 1);
            }
        }
        if (listOpen && !satelliteNames.isEmpty()) {
            drawSatellitePicker(graphics, mouseX, mouseY);
        }
    }

    private void drawSatellitePicker(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = leftPos + 62;
        int y = topPos + 74;
        int w = 106;
        int rowH = 12;
        int visible = Math.min(4, satelliteNames.size());
        UiDraw.fill(graphics, x - 2, y - 2, w + 4, visible * rowH + 4, PipeThemes.CRAFTING.panelInset());
        UiDraw.border(graphics, PipeThemes.CRAFTING, x - 2, y - 2, w + 4, visible * rowH + 4);
        for (int i = 0; i < visible; i++) {
            int idx = listIndex + i;
            if (idx >= satelliteNames.size()) {
                break;
            }
            int rowY = y + i * rowH;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + rowH;
            if (hover) {
                UiDraw.fill(graphics, x, rowY, w, rowH, PipeThemes.CRAFTING.buttonFillHover());
            }
            graphics.text(font, Component.literal(satelliteNames.get(idx)), x + 2, rowY + 2, PanelStyle.LABEL, false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (listOpen && !satelliteNames.isEmpty()) {
            int x = leftPos + 62;
            int y = topPos + 74;
            int w = 106;
            int rowH = 12;
            int visible = Math.min(4, satelliteNames.size());
            for (int i = 0; i < visible; i++) {
                int idx = listIndex + i;
                if (idx >= satelliteNames.size()) {
                    break;
                }
                int rowY = y + i * rowH;
                if (event.x() >= x && event.x() < x + w && event.y() >= rowY && event.y() < rowY + rowH) {
                    push(menu.pattern().withSatellite(satelliteNames.get(idx)));
                    listOpen = false;
                    return true;
                }
            }
        }
        var hit = CraftingPipeLayout.hitTest(event.x(), event.y(), leftPos, topPos);
        if (hit.isPresent()) {
            int index = hit.getAsInt();
            ItemStack carried = minecraft.player != null
                    ? minecraft.player.containerMenu.getCarried()
                    : ItemStack.EMPTY;
            ItemStack ghost = carried.isEmpty() ? ItemStack.EMPTY : carried.copyWithCount(1);
            CraftPattern pattern = menu.pattern();
            CraftPattern base = pattern.kind() != CraftPattern.Kind.SHAPED
                    ? CraftPattern.shaped(pattern.inputs(), pattern.primaryOutput(), pattern.satellite())
                    : pattern;
            if (index == GhostCraftingLayout.RESULT_INDEX) {
                push(base.withOutput(0, ghost));
            } else {
                push(base.withInput(index, ghost));
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        if (listOpen) {
            return;
        }
        ItemStack ghost = CraftingPipeLayout.ghostAt(menu.pattern(), mouseX, mouseY, leftPos, topPos);
        if (ghost.isEmpty()) {
            return;
        }
        graphics.setTooltipForNextFrame(
                font, List.of(ghost.getHoverName()), Optional.empty(), ghost, mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (listOpen && satelliteNames.size() > 4) {
            if (scrollY > 0) {
                listIndex = Math.max(0, listIndex - 1);
            } else if (scrollY < 0) {
                listIndex = Math.min(satelliteNames.size() - 4, listIndex + 1);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
