package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.client.ClientCraftMonitor;
import com.bobby.bobbypipes.menu.AutocraftMonitorMenu;
import com.bobby.bobbypipes.network.payload.CancelCraftJobPayload;
import com.bobby.bobbypipes.network.payload.CraftMonitorPayload;
import com.bobby.bobbycore.client.gui.ThemedContainerScreen;
import com.bobby.bobbycore.client.gui.draw.ScreenHeader;
import com.bobby.bobbycore.client.gui.font.BobbyFonts;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import com.bobby.bobbycore.client.gui.scroll.ScrollController;
import com.bobby.bobbycore.client.gui.scroll.ScrollModel;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import com.bobby.bobbycore.client.gui.widget.Panel;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Scrollable card list of pending autocraft jobs and recent failures.
 * Uses BobbyCore {@link ScrollController} with one scroll unit per card.
 */
public class AutocraftMonitorScreen extends ThemedContainerScreen<AutocraftMonitorMenu> {

    private static final int PANEL_W = 220;
    private static final int PANEL_H = 200 + GuiLayout.CONTENT_TOP_PAD;
    private static final int LIST_X = GuiLayout.contentSlotOriginX();
    private static final int LIST_Y = GuiLayout.slottedContentTop();
    private static final int LIST_W = 190;
    private static final int LIST_H = 172;
    private static final int CARD_H = 44;
    private static final int CARD_GAP = 4;
    private static final int CARD_STRIDE = CARD_H + CARD_GAP;
    private static final int SCROLL_W = 6;
    /** How many full card strides fit in the list viewport. */
    private static final int VISIBLE_CARDS = Math.max(1, (LIST_H + CARD_GAP) / CARD_STRIDE);

    private final ScrollModel scrollModel = new ScrollModel(VISIBLE_CARDS, 1);
    private final ScrollController scrollController = new ScrollController(scrollModel).setTheme(PipeThemes.AUTOCRAFT_MONITOR);
    private CraftMonitorPayload.Card hoveredCard;
    private ItemStack headerIcon = ItemStack.EMPTY;
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

    public AutocraftMonitorScreen(AutocraftMonitorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, PANEL_W, PANEL_H);
        this.inventoryLabelY = 1000;
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = PipeThemes.AUTOCRAFT_MONITOR.titlePadY();
    }

    @Override
    protected UiTheme uiTheme() {
        return PipeThemes.AUTOCRAFT_MONITOR;
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        scrollModel.setScrollRows(0);
        layoutScrollTrack();
    }

    private void layoutScrollTrack() {
        scrollController.setTrackBounds(
                leftPos + LIST_X + LIST_W + 2,
                topPos + LIST_Y,
                SCROLL_W,
                LIST_H);
    }

    private void syncScrollModel(int cardCount) {
        scrollModel.setVisibleRows(VISIBLE_CARDS);
        scrollModel.setTotalRows(Math.max(1, cardCount));
        layoutScrollTrack();
    }

    private boolean isOverList(double mouseX, double mouseY) {
        return mouseX >= leftPos + LIST_X
                && mouseX < leftPos + LIST_X + LIST_W + SCROLL_W + 2
                && mouseY >= topPos + LIST_Y
                && mouseY < topPos + LIST_Y + LIST_H;
    }

    @Override
    public void removed() {
        ClientCraftMonitor.clear();
        super.removed();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        Panel.draw(graphics, PipeThemes.AUTOCRAFT_MONITOR, leftPos, topPos, PANEL_W, PANEL_H);
        PipeGui.drawContentPaneBorder(
                graphics, PipeThemes.AUTOCRAFT_MONITOR, leftPos, topPos, PANEL_W, PANEL_H, -1);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ScreenHeader.draw(graphics, font, PipeThemes.AUTOCRAFT_MONITOR, title, headerIcon, PanelStyle.LABEL);
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

        syncScrollModel(cards.size());
        int scrollRows = scrollModel.scrollRows();
        int scrollOff = scrollRows * CARD_STRIDE;

        graphics.enableScissor(leftPos + LIST_X, topPos + LIST_Y,
                leftPos + LIST_X + LIST_W, topPos + LIST_Y + LIST_H);
        int drawY = topPos + LIST_Y - scrollOff;
        for (CraftMonitorPayload.Card card : cards) {
            if (drawY + CARD_H >= topPos + LIST_Y && drawY <= topPos + LIST_Y + LIST_H) {
                renderCard(graphics, leftPos + LIST_X, drawY, card, mouseX, mouseY);
            }
            drawY += CARD_STRIDE;
        }
        graphics.disableScissor();

        layoutScrollTrack();
        scrollController.draw(graphics, mouseX, mouseY);
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

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "...";
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        layoutScrollTrack();
        syncScrollModel(ClientCraftMonitor.cards().size());
        if (scrollController.mouseClicked(event)) {
            return true;
        }
        for (CancelHit hit : cancelHits) {
            if (hit.covers(event.x(), event.y())) {
                ClientPacketDistributor.sendToServer(new CancelCraftJobPayload(hit.jobId()));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (scrollController.mouseDragged(event)) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        scrollController.mouseReleased(event);
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        syncScrollModel(ClientCraftMonitor.cards().size());
        layoutScrollTrack();
        if (scrollController.mouseScrolled(mouseX, mouseY, scrollY, isOverList(mouseX, mouseY))) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
