package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.menu.PowerJunctionMenu;
import com.bobby.bobbypipes.network.payload.PowerJunctionSyncPayload;
import com.bobby.bobbypipes.logistics.power.PowerSpendKind;
import com.bobby.bobbycore.client.gui.ThemedContainerScreen;
import com.bobby.bobbycore.client.gui.draw.ScreenHeader;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import com.bobby.bobbycore.client.gui.scroll.ScrollController;
import com.bobby.bobbycore.client.gui.scroll.ScrollModel;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Power junction UI: buffer readout plus one usage line per spend kind over the sample history,
 * with a scrollable legend showing every kind's current draw (zero included).
 */
public class PowerJunctionScreen extends ThemedContainerScreen<PowerJunctionMenu> {

    private static final int SIDE_PAD = 8;
    private static final int CONTENT_TOP = 6;
    private static final int STATUS_H = 36;
    private static final int GRAPH_H = 60;
    private static final int GRAPH_GAP = 6;
    private static final int LEGEND_ROW = 11;
    private static final int LEGEND_VISIBLE = 5;
    private static final int LEGEND_H = LEGEND_ROW * LEGEND_VISIBLE;
    private static final int SCROLL_W = 6;
    private static final int CONTENT_BOTTOM = 8;

    private static final int GRAPH_BG = 0xFF121212;
    private static final int GRAPH_GRID = 0xFF232323;
    private static final int DIM_ALPHA = 0x55;

    private static final int[] KIND_COLORS = {
            0xFFC16532, // PROVIDER
            0xFF29946D, // REQUEST
            0xFF3B2994, // SUPPLIER
            0xFF297A94, // PASSIVE_SUPPLIER
            0xFF792994, // CRAFTING
            0xFF94295F, // SATELLITE
            0xFFE08A4A, // ENERGY_PROVIDER
            0xFF4CB892, // ENERGY_REQUEST
            0xFF5A4AB8, // ENERGY_SUPPLIER
            0xFFB87A4A, // FLUID_PROVIDER
            0xFF3AA88A, // FLUID_REQUEST
            0xFF4A6AB8, // FLUID_SUPPLIER
            0xFF5A2D9E, // LINK_SAME
            0xFF9E2D5A, // LINK_INTERDIM
    };

    private final ScrollModel scrollModel =
            new ScrollModel(LEGEND_VISIBLE, PowerSpendKind.VALUES.length);
    private final ScrollController scrollController =
            new ScrollController(scrollModel).setTheme(PipeThemes.POWER_JUNCTION);

    private ItemStack headerIcon = ItemStack.EMPTY;
    private int hoverKind = -1;

    public PowerJunctionScreen(PowerJunctionMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, GuiLayout.STANDARD_PANEL_WIDTH, panelHeight());
        this.inventoryLabelY = 1000;
        setHelpTooltip(Component.translatable("gui.bobbypipes.help.power_junction"));
    }

    @Override
    protected UiTheme uiTheme() {
        return PipeThemes.POWER_JUNCTION;
    }

    private static int panelHeight() {
        return PipeThemes.POWER_JUNCTION.headerHeight()
                + CONTENT_TOP
                + STATUS_H
                + 4
                + GRAPH_H
                + GRAPH_GAP
                + LEGEND_H
                + CONTENT_BOTTOM;
    }

    private int contentX() {
        return leftPos + SIDE_PAD;
    }

    private int contentW() {
        return imageWidth - SIDE_PAD * 2;
    }

    private int statusY() {
        return topPos + PipeThemes.POWER_JUNCTION.headerHeight() + CONTENT_TOP;
    }

    private int graphY() {
        return statusY() + STATUS_H + 4;
    }

    private int legendY() {
        return graphY() + GRAPH_H + GRAPH_GAP;
    }

    private int legendW() {
        return contentW() - SCROLL_W - 2;
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = PipeThemes.POWER_JUNCTION.titlePadY();
        scrollModel.setScrollRows(0);
        layoutScrollTrack();
    }

    private void layoutScrollTrack() {
        scrollController.setTrackBounds(
                contentX() + legendW() + 2, legendY(), SCROLL_W, LEGEND_H);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        PipeGui.drawPanelAndMenuSlots(
                graphics, PipeThemes.POWER_JUNCTION, leftPos, topPos, imageWidth, imageHeight, menu.slots);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ScreenHeader.draw(graphics, font, PipeThemes.POWER_JUNCTION, title, headerIcon,
                PanelStyle.LABEL);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        layoutScrollTrack();
        hoverKind = legendHoverKind(mouseX, mouseY);

        int x = contentX();
        int y = statusY();
        graphics.text(font, menu.energy() + " / " + menu.capacity() + " FE",
                x, y, PanelStyle.LABEL, false);
        graphics.text(font,
                Component.translatable(menu.powered()
                        ? "gui.bobbypipes.power_junction.powered"
                        : "gui.bobbypipes.power_junction.unpowered"),
                x, y + 10,
                menu.powered() ? 0xFF55FF55 : 0xFFFF5555,
                false);
        graphics.text(font,
                Component.translatable("gui.bobbypipes.power_junction.rates",
                        menu.windowIn(), menu.windowOut()),
                x, y + 20, PanelStyle.LABEL, false);

        drawBufferBar(graphics, x, y + 30, contentW(), 4);
        drawGraph(graphics, x, graphY(), contentW(), GRAPH_H, mouseX, mouseY);
        drawLegend(graphics, x, legendY(), mouseX, mouseY);
        scrollController.draw(graphics, mouseX, mouseY);
    }

    private void drawBufferBar(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0xFF1A1A1A);
        float fill = menu.capacity() <= 0 ? 0f : (float) menu.energy() / (float) menu.capacity();
        int filled = Math.round(w * Mth.clamp(fill, 0f, 1f));
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + h, 0xFFC4A035);
        }
    }

    /**
     * One polyline per spend kind across the retained samples. Kinds that never spent still get a
     * flat line on the baseline so the legend and the graph always agree.
     */
    private void drawGraph(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
                           int mouseX, int mouseY) {
        graphics.fill(x, y, x + w, y + h, GRAPH_BG);
        for (int i = 1; i < 4; i++) {
            int gy = y + Math.round(h * (i / 4f));
            graphics.fill(x, gy, x + w, gy + 1, GRAPH_GRID);
        }

        List<PowerJunctionSyncPayload.Frame> frames = menu.frames();
        if (frames.isEmpty()) {
            graphics.text(font,
                    Component.translatable("gui.bobbypipes.power_junction.graph_empty"),
                    x + 4, y + h / 2 - 4, 0xFF666666, false);
            return;
        }

        int max = 1;
        for (PowerJunctionSyncPayload.Frame frame : frames) {
            for (int k = 0; k < PowerSpendKind.VALUES.length; k++) {
                max = Math.max(max, kindValue(frame, k));
            }
        }

        int cols = frames.size();
        float stepX = cols <= 1 ? 0f : (w - 1) / (float) (cols - 1);
        int hoverCol = -1;
        if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) {
            hoverCol = cols <= 1 ? 0
                    : Mth.clamp(Math.round((mouseX - x) / stepX), 0, cols - 1);
            int hx = x + Math.round(hoverCol * stepX);
            graphics.fill(hx, y, hx + 1, y + h, 0xFF3A3A3A);
        }

        // Dimmed kinds first so the hovered series reads on top.
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < PowerSpendKind.VALUES.length; k++) {
                boolean focused = hoverKind < 0 || hoverKind == k;
                if ((pass == 0) == focused) {
                    continue;
                }
                int color = KIND_COLORS[k % KIND_COLORS.length];
                if (!focused) {
                    color = (color & 0x00FFFFFF) | (DIM_ALPHA << 24);
                } else if (hoverKind == k) {
                    color = brighten(color);
                }
                drawSeries(graphics, frames, k, x, y, w, h, stepX, max, color);
            }
        }

        graphics.text(font, max + " FE/s",
                x + w - font.width(max + " FE/s") - 3, y + 2, 0xFF777777, false);

        if (hoverCol >= 0) {
            graphics.setTooltipForNextFrame(font, sampleTooltip(frames.get(hoverCol)),
                    Optional.empty(), ItemStack.EMPTY, mouseX, mouseY);
        }
    }

    private void drawSeries(GuiGraphicsExtractor graphics,
                            List<PowerJunctionSyncPayload.Frame> frames,
                            int kind, int x, int y, int w, int h, float stepX, int max, int color) {
        int prevX = 0;
        int prevY = 0;
        for (int i = 0; i < frames.size(); i++) {
            int px = frames.size() <= 1 ? x + w - 1 : x + Math.round(i * stepX);
            int py = y + h - 1 - Math.round((kindValue(frames.get(i), kind) / (float) max) * (h - 2));
            if (i > 0) {
                drawLine(graphics, prevX, prevY, px, py, color);
            } else if (frames.size() == 1) {
                graphics.fill(px, py, px + 1, py + 1, color);
            }
            prevX = px;
            prevY = py;
        }
    }

    /** Bresenham on 1x1 fills — {@code graphics} has no line primitive. */
    private static void drawLine(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0;
        int y = y0;
        while (true) {
            graphics.fill(x, y, x + 1, y + 1, color);
            if (x == x1 && y == y1) {
                return;
            }
            int e2 = err * 2;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private List<Component> sampleTooltip(PowerJunctionSyncPayload.Frame frame) {
        List<Component> tip = new ArrayList<>();
        tip.add(Component.translatable("gui.bobbypipes.power_junction.sample",
                frame.totalOut(), frame.totalIn()));
        for (int k = 0; k < PowerSpendKind.VALUES.length; k++) {
            int amount = kindValue(frame, k);
            if (amount > 0 || hoverKind == k) {
                tip.add(Component.literal(kindLabel(PowerSpendKind.VALUES[k]) + ": " + amount));
            }
        }
        return tip;
    }

    /** Single scrollable column: every kind, always, with its current per-second draw. */
    private void drawLegend(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
        int w = legendW();
        graphics.fill(x, y, x + w, y + LEGEND_H, 0xFF161616);
        graphics.enableScissor(x, y, x + w, y + LEGEND_H);
        int rowY = y - scrollModel.scrollRows() * LEGEND_ROW;
        for (int k = 0; k < PowerSpendKind.VALUES.length; k++) {
            if (rowY + LEGEND_ROW > y && rowY < y + LEGEND_H) {
                drawLegendRow(graphics, x, rowY, w, k, hoverKind == k);
            }
            rowY += LEGEND_ROW;
        }
        graphics.disableScissor();
    }

    private void drawLegendRow(GuiGraphicsExtractor graphics, int x, int y, int w, int kind, boolean hovered) {
        if (hovered) {
            graphics.fill(x, y, x + w, y + LEGEND_ROW, 0xFF2A2A2A);
        }
        int color = KIND_COLORS[kind % KIND_COLORS.length];
        graphics.fill(x + 2, y + 2, x + 8, y + 8, color);
        int current = currentDraw(kind);
        graphics.text(font, kindLabel(PowerSpendKind.VALUES[kind]), x + 12, y + 2,
                current > 0 ? PanelStyle.LABEL : 0xFF777777, false);
        String value = current + " FE/s";
        graphics.text(font, value, x + w - font.width(value) - 3, y + 2,
                current > 0 ? 0xFFDDDDDD : 0xFF666666, false);
    }

    /** Draw in the newest retained sample, or 0 when the kind spent nothing. */
    private int currentDraw(int kind) {
        List<PowerJunctionSyncPayload.Frame> frames = menu.frames();
        return frames.isEmpty() ? 0 : kindValue(frames.get(frames.size() - 1), kind);
    }

    private static int kindValue(PowerJunctionSyncPayload.Frame frame, int kind) {
        int[] byKind = frame.byKind();
        return kind < byKind.length ? byKind[kind] : 0;
    }

    private int legendHoverKind(double mouseX, double mouseY) {
        if (!isOverLegend(mouseX, mouseY)) {
            return -1;
        }
        int row = (int) ((mouseY - legendY()) / LEGEND_ROW) + scrollModel.scrollRows();
        return row >= 0 && row < PowerSpendKind.VALUES.length ? row : -1;
    }

    private boolean isOverLegend(double mouseX, double mouseY) {
        return mouseX >= contentX() && mouseX < contentX() + legendW()
                && mouseY >= legendY() && mouseY < legendY() + LEGEND_H;
    }

    private static String kindLabel(PowerSpendKind kind) {
        return Component.translatable("gui.bobbypipes.power_junction.kind."
                + kind.name().toLowerCase()).getString();
    }

    private static int brighten(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 40);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 40);
        int b = Math.min(255, (argb & 0xFF) + 40);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        layoutScrollTrack();
        if (scrollController.mouseClicked(event)) {
            return true;
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
        layoutScrollTrack();
        if (scrollController.mouseScrolled(mouseX, mouseY, scrollY, isOverLegend(mouseX, mouseY))) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
