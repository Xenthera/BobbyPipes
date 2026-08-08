package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbycore.client.gui.ThemedContainerScreen;
import com.bobby.bobbycore.client.gui.draw.ScreenHeader;
import com.bobby.bobbycore.client.gui.font.BobbyFonts;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import com.bobby.bobbycore.client.gui.widget.UiButton;
import com.bobby.bobbycore.client.gui.widget.UiTextBox;
import com.bobby.bobbypipes.client.ClientFluidRequestGui;
import com.bobby.bobbypipes.menu.FluidRequestMenu;
import com.bobby.bobbypipes.network.payload.FluidStockPayload;
import com.bobby.bobbypipes.client.ClientRequestOptions;
import com.bobby.bobbypipes.network.payload.RequestFluidPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

import java.util.List;
import java.util.Optional;

/**
 * Browse network fluid stock and submit a request. Same shape as {@link RequestScreen} but
 * simpler: no search/sort/craftables, fluid never crafts and catalogs are small.
 *
 * <p>Layout: header -> scrollable fluid grid -> amount + request.
 */
public class FluidRequestScreen extends ThemedContainerScreen<FluidRequestMenu> {

    private static final int CONTENT_X = GuiLayout.contentSlotOriginX();
    private static final int GRID_GAP = 4;
    private static final int ACTION_H = 16;
    private static final int AMOUNT_W = 60;
    private static final int TOOL_GAP = 3;
    private static final int PARTIAL_W = 22;

    private FluidGrid grid;
    private UiTextBox amount;
    private UiButton requestButton;
    private UiButton partialButton;
    private FluidResource selected = FluidResource.EMPTY;
    private ItemStack headerIcon = ItemStack.EMPTY;

    public FluidRequestScreen(FluidRequestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, panelWidth(), panelHeight());
        this.inventoryLabelY = 1000;
        setHelpTooltip(Component.translatable("gui.bobbypipes.help.fluid_request"));
    }

    @Override
    protected UiTheme uiTheme() {
        return PipeThemes.REQUEST;
    }

    private static int panelWidth() {
        return CONTENT_X + FluidGrid.COLUMNS * FluidGrid.SLOT + 1 + FluidGrid.SCROLLER_WIDTH + CONTENT_X;
    }

    private static int panelHeight() {
        int gridY = gridY(PipeThemes.REQUEST);
        int actionY = gridY + FluidGrid.VISIBLE_ROWS * FluidGrid.SLOT + GRID_GAP;
        return actionY + ACTION_H + GuiLayout.CONTENT_BOTTOM_PAD;
    }

    private static int gridY(UiTheme theme) {
        return theme.headerHeight() + GRID_GAP;
    }

    private int gridY() {
        return gridY(PipeThemes.REQUEST);
    }

    private int actionY() {
        return gridY() + FluidGrid.VISIBLE_ROWS * FluidGrid.SLOT + GRID_GAP;
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = PipeThemes.REQUEST.titlePadY();

        grid = new FluidGrid(leftPos + CONTENT_X, topPos + gridY(), PipeThemes.REQUEST);

        int ay = actionY();
        amount = UiTextBox.builder(font, Component.translatable("gui.bobbypipes.fluid_request.amount"))
                .bounds(leftPos + CONTENT_X, topPos + ay, AMOUNT_W, ACTION_H)
                .theme(PipeThemes.REQUEST)
                .maxLength(9)
                .filter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit))
                .value("1000")
                .build();
        addRenderableWidget(amount);

        int requestX = leftPos + CONTENT_X + AMOUNT_W + TOOL_GAP;
        int partialX = leftPos + imageWidth - CONTENT_X - PARTIAL_W;
        int requestW = partialX - TOOL_GAP - requestX;
        requestButton = UiButton.builder(
                        Component.translatable("gui.bobbypipes.fluid_request.submit"),
                        button -> sendRequest())
                .bounds(requestX, topPos + ay, Math.max(48, requestW), ACTION_H)
                .build()
                .setTheme(PipeThemes.REQUEST);
        addRenderableWidget(requestButton);

        partialButton = UiButton.builder(partialLabel(), button -> {
                    ClientRequestOptions.toggleFluidAllowPartial();
                    button.setMessage(BobbyFonts.apply(partialLabel()));
                    button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                            partialTooltip()));
                })
                .bounds(partialX, topPos + ay, PARTIAL_W, ACTION_H)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(partialTooltip()))
                .build()
                .setTheme(PipeThemes.REQUEST);
        addRenderableWidget(partialButton);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        requestButton.active = !selected.isEmpty() && parseAmount() > 0;
    }

    private void sendRequest() {
        int wanted = parseAmount();
        if (selected.isEmpty() || wanted <= 0) {
            return;
        }
        ClientPacketDistributor.sendToServer(new RequestFluidPayload(
                menu.pos(), selected, wanted, ClientRequestOptions.fluidAllowPartial()));
    }

    /** Approximate sign while a short order may still ship, equals sign for exact-only. */
    private static Component partialLabel() {
        return BobbyFonts.literal(ClientRequestOptions.fluidAllowPartial() ? "≈" : "=");
    }

    private static Component partialTooltip() {
        return Component.translatable(ClientRequestOptions.fluidAllowPartial()
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

    private List<FluidStockPayload.Entry> stock() {
        return ClientFluidRequestGui.stock();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (grid != null) {
            grid.draw(graphics, font, stock(), selected, mouseX, mouseY);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ScreenHeader.draw(graphics, font, PipeThemes.REQUEST, title, headerIcon, PanelStyle.LABEL);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        PipeGui.drawPanelAndMenuSlots(graphics, PipeThemes.REQUEST, leftPos, topPos, imageWidth, imageHeight, menu.slots);
        PipeGui.drawSlotGrid(
                graphics,
                PipeThemes.REQUEST,
                leftPos + CONTENT_X,
                topPos + gridY(),
                FluidGrid.COLUMNS,
                FluidGrid.VISIBLE_ROWS,
                FluidGrid.SLOT);
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        if (grid == null) {
            return;
        }
        Optional<FluidStockPayload.Entry> hit = grid.entryAt(stock(), mouseX, mouseY);
        if (hit.isEmpty()) {
            return;
        }
        FluidStockPayload.Entry entry = hit.get();
        ItemStack stack = FluidGrid.iconFor(entry.fluid());
        List<Component> lines = new java.util.ArrayList<>();
        lines.add(entry.fluid().getFluidType().getDescription());
        lines.add(Component.translatable("gui.bobbypipes.fluid_request.stored", entry.amountMb())
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        graphics.setTooltipForNextFrame(font, lines, Optional.empty(), stack, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        List<FluidStockPayload.Entry> entries = stock();
        if (grid != null && grid.mouseClicked(event, entries.size())) {
            return true;
        }
        if (grid != null) {
            Optional<FluidStockPayload.Entry> hit = grid.entryAt(entries, event.x(), event.y());
            if (hit.isPresent()) {
                FluidStockPayload.Entry entry = hit.get();
                selected = entry.fluid();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (grid != null) {
            grid.mouseReleased(event);
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (grid != null && grid.mouseDragged(event, stock().size())) {
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (grid != null && grid.mouseScrolled(mouseX, mouseY, scrollY, stock().size())) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
