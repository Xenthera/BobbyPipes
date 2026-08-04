package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.client.ClientCraftMonitor;
import com.bobby.bobbypipes.menu.AutocraftMonitorMenu;
import com.bobby.bobbypipes.network.payload.CraftMonitorPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Scrollable card list of pending autocraft jobs and recent failures.
 */
public class AutocraftMonitorScreen extends AbstractContainerScreen<AutocraftMonitorMenu> {

    private static final int PANEL_W = 220;
    private static final int PANEL_H = 200;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 18;
    private static final int LIST_W = 190;
    private static final int LIST_H = 172;
    private static final int CARD_H = 44;
    private static final int CARD_GAP = 4;
    private static final int SCROLL_W = 6;

    private double scrollOff;
    private CraftMonitorPayload.Card hoveredCard;
    /**
     * Cancel boxes from the last frame, rebuilt every render.
     *
     * <p>Cards scroll, so hit testing against fixed widgets would drift. Recording the
     * rectangles as they are drawn keeps the click and the pixels in step.
     */
    private final List<CancelHit> cancelHits = new ArrayList<>();

    private record CancelHit(int x, int y, long jobId) {
        private static final int SIZE = 12;

        boolean covers(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + SIZE && mouseY >= y && mouseY < y + SIZE;
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event,
                               boolean doubleClick) {
        for (CancelHit hit : cancelHits) {
            if (hit.covers(event.x(), event.y())) {
                net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                        new com.bobby.bobbypipes.network.payload.CancelCraftJobPayload(hit.jobId()));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    public AutocraftMonitorScreen(AutocraftMonitorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, PANEL_W, PANEL_H);
        this.inventoryLabelY = 1000;
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        scrollOff = 0;
    }

    @Override
    public void removed() {
        ClientCraftMonitor.clear();
        super.removed();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + PANEL_W, y + PANEL_H, 0xFF_2A_2A_2E);
        graphics.fill(x + 1, y + 1, x + PANEL_W - 1, y + PANEL_H - 1, 0xFF_3A_3A_40);
        graphics.fill(x + LIST_X - 2, y + LIST_Y - 2,
                x + LIST_X + LIST_W + SCROLL_W + 4, y + LIST_Y + LIST_H + 2, 0xFF_1E_1E_22);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, title, titleLabelX, titleLabelY, 0xFF_E0_E0_E0, false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        hoveredCard = null;

        if (!ClientCraftMonitor.linked()) {
            graphics.text(font,
                    Component.translatable("gui.bobbypipes.autocraft_monitor.unlinked"),
                    leftPos + LIST_X + 4, topPos + LIST_Y + 8, 0xFF_FF_88_88, false);
            graphics.text(font,
                    Component.translatable("gui.bobbypipes.autocraft_monitor.unlinked.hint"),
                    leftPos + LIST_X + 4, topPos + LIST_Y + 22, 0xFF_AA_AA_AA, false);
            return;
        }

        List<CraftMonitorPayload.Card> cards = ClientCraftMonitor.cards();
        cancelHits.clear();
        if (cards.isEmpty()) {
            graphics.text(font,
                    Component.translatable("gui.bobbypipes.autocraft_monitor.empty"),
                    leftPos + LIST_X + 4, topPos + LIST_Y + 8, 0xFF_C0_C0_C0, false);
            return;
        }

        int contentH = cards.size() * (CARD_H + CARD_GAP) - CARD_GAP;
        int maxScroll = Math.max(0, contentH - LIST_H);
        scrollOff = Mth.clamp(scrollOff, 0, maxScroll);

        graphics.enableScissor(leftPos + LIST_X, topPos + LIST_Y,
                leftPos + LIST_X + LIST_W, topPos + LIST_Y + LIST_H);
        int drawY = topPos + LIST_Y - (int) scrollOff;
        for (CraftMonitorPayload.Card card : cards) {
            if (drawY + CARD_H >= topPos + LIST_Y && drawY <= topPos + LIST_Y + LIST_H) {
                renderCard(graphics, leftPos + LIST_X, drawY, card, mouseX, mouseY);
            }
            drawY += CARD_H + CARD_GAP;
        }
        graphics.disableScissor();

        if (maxScroll > 0) {
            int trackX = leftPos + LIST_X + LIST_W + 2;
            int trackY = topPos + LIST_Y;
            graphics.fill(trackX, trackY, trackX + SCROLL_W, trackY + LIST_H, 0xFF_10_10_14);
            int thumbH = Math.max(12, LIST_H * LIST_H / contentH);
            int thumbY = trackY + (int) ((LIST_H - thumbH) * (scrollOff / maxScroll));
            graphics.fill(trackX, thumbY, trackX + SCROLL_W, thumbY + thumbH, 0xFF_88_88_90);
        }
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        if (hoveredCard == null) {
            return;
        }
        CraftMonitorPayload.Card card = hoveredCard;
        ItemStack stack = card.output();
        List<Component> tip = new ArrayList<>();
        tip.add(stack.isEmpty() ? Component.literal("?") : stack.getHoverName());
        if (!card.detail().isEmpty()) {
            tip.add(Component.literal(card.detail()));
        }
        tip.add(Component.literal("Crafter " + card.crafter().toShortString()));
        for (CraftMonitorPayload.Want want : card.wants()) {
            tip.add(Component.literal((want.fromCraft() ? "craft " : "need ")
                    + want.stack().getCount() + "x " + want.stack().getHoverName().getString()));
        }
        graphics.setTooltipForNextFrame(font, tip, Optional.empty(), stack, mouseX, mouseY);
    }

    private void renderCard(GuiGraphicsExtractor graphics,
                            int x,
                            int y,
                            CraftMonitorPayload.Card card,
                            int mouseX,
                            int mouseY) {
        int bg = switch (card.status()) {
            case FAILED -> 0xFF_4A_22_22;
            case QUEUED -> 0xFF_3A_3A_28;
            case WAIT_OUTPUT -> 0xFF_2A_3A_4A;
            case GATHER -> 0xFF_2A_2A_32;
        };
        graphics.fill(x, y, x + LIST_W, y + CARD_H, bg);
        graphics.fill(x, y, x + 3, y + CARD_H, statusAccent(card.status()));

        ItemStack stack = card.output();
        if (!stack.isEmpty()) {
            graphics.item(stack, x + 8, y + 6);
            graphics.itemDecorations(font, stack, x + 8, y + 6);
        }

        String statusLabel = switch (card.status()) {
            case GATHER -> "Gather";
            case WAIT_OUTPUT -> "Craft";
            case QUEUED -> "Queued";
            case FAILED -> "Failed";
        };
        graphics.text(font, statusLabel, x + 30, y + 4, 0xFF_FF_FF_FF, false);
        if (card.runsRemaining() > 0) {
            graphics.text(font, "x" + card.runsRemaining(), x + 80, y + 4, 0xFF_DD_DD_88, false);
        }

        // Jobs no longer expire on a timer, so there is no countdown to show. A stuck
        // job is cleared with the cancel control instead.
        if (card.jobId() != 0L) {
            int cancelX = x + LIST_W - 18;
            boolean over = mouseX >= cancelX && mouseX < cancelX + 12
                    && mouseY >= y + 4 && mouseY < y + 16;
            graphics.fill(cancelX, y + 4, cancelX + 12, y + 16,
                    over ? 0xFF_C0_3A_3A : 0xFF_6A_2A_2A);
            graphics.text(font, "x", cancelX + 4, y + 6, 0xFF_FF_E0_E0, false);
            cancelHits.add(new CancelHit(cancelX, y + 4, card.jobId()));
        }

        graphics.text(font, truncate(card.detail(), 34), x + 8, y + 30, 0xFF_BB_BB_BB, false);

        int wantX = x + LIST_W - 8;
        List<CraftMonitorPayload.Want> wants = card.wants();
        for (int i = Math.min(3, wants.size()) - 1; i >= 0; i--) {
            wantX -= 18;
            ItemStack want = wants.get(i).stack();
            graphics.item(want, wantX, y + 24);
            graphics.itemDecorations(font, want, wantX, y + 24);
            if (wants.get(i).fromCraft()) {
                graphics.fill(wantX, y + 24, wantX + 2, y + 40, 0xFF_FF_AA_00);
            }
        }
        if (wants.size() > 3) {
            graphics.text(font, "+" + (wants.size() - 3), wantX - 14, y + 28, 0xFF_CC_CC_CC, false);
        }

        if (mouseX >= x && mouseX < x + LIST_W && mouseY >= y && mouseY < y + CARD_H
                && mouseX >= leftPos + LIST_X && mouseX < leftPos + LIST_X + LIST_W
                && mouseY >= topPos + LIST_Y && mouseY < topPos + LIST_Y + LIST_H) {
            hoveredCard = card;
        }
    }

    private static int statusAccent(CraftMonitorPayload.Status status) {
        return switch (status) {
            case FAILED -> 0xFF_FF_44_44;
            case QUEUED -> 0xFF_CC_CC_44;
            case WAIT_OUTPUT -> 0xFF_44_AA_FF;
            case GATHER -> 0xFF_66_DD_66;
        };
    }

    private static void drawBar(GuiGraphicsExtractor graphics,
                                int x, int y, int w, int h,
                                int left, int max, int fill) {
        graphics.fill(x, y, x + w, y + h, 0xFF_15_15_18);
        if (max <= 0) {
            return;
        }
        int filled = Mth.clamp(w * left / max, 0, w);
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + h, fill);
        }
    }

    private static String formatTicks(int ticks) {
        int sec = Math.max(0, ticks) / 20;
        if (sec >= 60) {
            return (sec / 60) + "m" + (sec % 60) + "s";
        }
        return sec + "s";
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "...";
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= leftPos + LIST_X && mouseX < leftPos + LIST_X + LIST_W + SCROLL_W
                && mouseY >= topPos + LIST_Y && mouseY < topPos + LIST_Y + LIST_H) {
            int contentH = ClientCraftMonitor.cards().size() * (CARD_H + CARD_GAP) - CARD_GAP;
            int maxScroll = Math.max(0, contentH - LIST_H);
            scrollOff = Mth.clamp(scrollOff - scrollY * 12, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
