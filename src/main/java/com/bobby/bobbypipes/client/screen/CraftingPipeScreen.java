package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.craft.CraftPattern;
import com.bobby.bobbypipes.menu.CraftingPipeMenu;
import com.bobby.bobbypipes.network.payload.ImportCraftPatternPayload;
import com.bobby.bobbypipes.network.payload.RequestSatelliteListPayload;
import com.bobby.bobbypipes.network.payload.SetCraftPatternPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.List;

/**
 * LP-style crafting pipe: row of 9 ingredient ghosts, result, import, one satellite.
 */
public class CraftingPipeScreen extends AbstractContainerScreen<CraftingPipeMenu> {

    private static final int GHOST_OVERLAY = 0x60_FF_FF_FF;

    /**
     * Button row, sat directly under the result slot.
     *
     * <p>Shorter than the default 20 so the row clears the result slot, which ends at
     * {@code RESULT_Y + SLOT}, without running into the player inventory below it.
     */
    private static final int BUTTON_Y = CraftingPipeLayout.RESULT_Y + CraftingPipeLayout.SLOT + 1;
    private static final int BUTTON_HEIGHT = 14;

    private List<String> satelliteNames = List.of();
    private int listIndex;
    private boolean listOpen;

    public CraftingPipeScreen(CraftingPipeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 166);
        this.inventoryLabelY = 74;
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 8;
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.bobbypipes.crafting.import"),
                        b -> ClientPacketDistributor.sendToServer(new ImportCraftPatternPayload(menu.pos())))
                .bounds(leftPos + 8, topPos + BUTTON_Y, 50, BUTTON_HEIGHT)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.bobbypipes.crafting.import.tip")))
                .build());
        addRenderableWidget(Button.builder(
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
        addRenderableWidget(Button.builder(
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
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                ModGuiTextures.CRAFTING_PIPE,
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
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY,
                PanelStyle.LABEL, false);
        String sat = menu.pattern().satellite();
        Component line = sat.isBlank()
                ? Component.translatable("gui.bobbypipes.crafting.satellite_none")
                : Component.translatable("gui.bobbypipes.crafting.satellite_current", sat);
        // Green only once this is actually routing somewhere. No satellite reads the same
        // as a satellite that has gone missing, because neither will deliver anywhere.
        boolean routing = !sat.isBlank()
                && (satelliteNames.isEmpty() || satelliteNames.contains(sat));
        // extractLabels is already translated to leftPos/topPos.
        graphics.text(font, line, 8, 46, routing ? PanelStyle.MARKER_GREEN : PanelStyle.MARKER_RED, false);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractContents(graphics, mouseX, mouseY, partialTick);
        CraftPattern pattern = menu.pattern();
        if (pattern.hasInputs() || !pattern.primaryOutput().isEmpty()) {
            CraftingPipeLayout.drawGhosts(graphics, font, leftPos, topPos, pattern);
            // Fade ghosts so they read as programming, not real items.
            for (int i = 0; i < 9; i++) {
                if (i < pattern.inputs().size() && !pattern.inputs().get(i).isEmpty()) {
                    int x = leftPos + CraftingPipeLayout.slotX(i) + 1;
                    int y = topPos + CraftingPipeLayout.slotY() + 1;
                    graphics.fill(x, y, x + 16, y + 16, GHOST_OVERLAY);
                }
            }
            if (!pattern.primaryOutput().isEmpty()) {
                int x = leftPos + CraftingPipeLayout.RESULT_X + 1;
                int y = topPos + CraftingPipeLayout.RESULT_Y + 1;
                graphics.fill(x, y, x + 16, y + 16, GHOST_OVERLAY);
            }
            if (pattern.hasSatellite()) {
                for (int slot = CraftPattern.SATELLITE_SLOT_START; slot < CraftPattern.SATELLITE_SLOT_END; slot++) {
                    int x = leftPos + CraftingPipeLayout.slotX(slot);
                    int y = topPos + CraftingPipeLayout.slotY();
                    graphics.fill(x, y, x + CraftingPipeLayout.SLOT, y + 1, 0xFF_3A_8A_FF);
                    graphics.fill(x, y + CraftingPipeLayout.SLOT - 1,
                            x + CraftingPipeLayout.SLOT, y + CraftingPipeLayout.SLOT, 0xFF_3A_8A_FF);
                    graphics.fill(x, y, x + 1, y + CraftingPipeLayout.SLOT, 0xFF_3A_8A_FF);
                    graphics.fill(x + CraftingPipeLayout.SLOT - 1, y,
                            x + CraftingPipeLayout.SLOT, y + CraftingPipeLayout.SLOT, 0xFF_3A_8A_FF);
                }
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
        graphics.fill(x - 2, y - 2, x + w + 2, y + visible * rowH + 2, 0xFF_20_20_20);
        for (int i = 0; i < visible; i++) {
            int idx = listIndex + i;
            if (idx >= satelliteNames.size()) {
                break;
            }
            int rowY = y + i * rowH;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + rowH;
            if (hover) {
                graphics.fill(x, rowY, x + w, rowY + rowH, 0xFF_3A_5A_8A);
            }
            graphics.text(font, Component.literal(satelliteNames.get(idx)), x + 2, rowY + 2, 0xFF_FF_FF_FF, false);
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
