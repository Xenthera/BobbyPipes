package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.client.ClientRequestGui;
import com.bobby.bobbypipes.menu.RequestMenu;
import com.bobby.bobbypipes.network.payload.NetworkStockPayload;
import com.bobby.bobbypipes.network.payload.RequestItemPayload;
import com.bobby.bobbypipes.network.payload.RequestResultPayload;
import com.bobby.bobbycore.client.gui.ThemedContainerScreen;
import com.bobby.bobbycore.client.gui.draw.ScreenHeader;
import com.bobby.bobbycore.client.gui.font.BobbyFonts;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import com.bobby.bobbycore.client.gui.widget.Panel;
import com.bobby.bobbycore.client.gui.widget.UiButton;
import com.bobby.bobbycore.client.gui.widget.UiTextBox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Browse network stock + craftables and submit a request.
 *
 * <p>Layout: header -> search toolbar (search + sort/group) -> scrollable grid -> qty + request.
 * Request outcomes go to chat; the shortfall modal still pops when nothing can be sourced.
 */
public class RequestScreen extends ThemedContainerScreen<RequestMenu> {

    private static final int CONTENT_X = GuiLayout.contentSlotOriginX();
    private static final int TOOLBAR_H = 14;
    private static final int TOOL_BTN_W = 22;
    private static final int TOOL_GAP = 3;
    private static final int GRID_GAP = 4;
    private static final int ACTION_H = 16;
    private static final int QTY_W = 40;

    private static final Component QTY_LABEL = BobbyFonts.literal("Qty:");

    /** How the grid is ordered. Cycled by the sort button. */
    private enum SortMode {
        NAME_ASC("A-Z"),
        NAME_DESC("Z-A"),
        COUNT_DESC("9-1"),
        COUNT_ASC("1-9");

        private final String label;

        SortMode(String label) {
            this.label = label;
        }

        SortMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** Non-null while the shortfall modal is up, which blocks the rest of the screen. */
    private List<RequestResultPayload.Shortfall> shortfallModal;
    private UiButton modalClose;
    /** Stops the modal reopening every tick while the same result is on screen. */
    private boolean shortfallShown;
    private SortMode sortMode = SortMode.NAME_ASC;
    private boolean groupCraftable = true;
    private UiButton sortButton;
    private UiButton groupButton;
    private UiTextBox search;
    private UiTextBox quantity;
    private UiButton requestButton;
    private StockItemGrid grid;
    private ItemResource selected = ItemResource.EMPTY;
    private List<NetworkStockPayload.Entry> filtered = List.of();
    private ItemStack headerIcon = ItemStack.EMPTY;

    public RequestScreen(RequestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, panelWidth(), panelHeight());
        this.inventoryLabelY = 1000;
        this.titleLabelX = ScreenHeader.titleX();
        this.titleLabelY = PipeThemes.REQUEST.titlePadY();
        setHelpTooltip(Component.translatable("gui.bobbypipes.help.request_pipe"));
    }

    @Override
    protected UiTheme uiTheme() {
        return PipeThemes.REQUEST;
    }

    private static int panelWidth() {
        // content pad + 9 slots + scrollbar + right pad
        return CONTENT_X + StockItemGrid.COLUMNS * StockItemGrid.SLOT + 1 + StockItemGrid.SCROLLER_WIDTH + CONTENT_X;
    }

    private static int panelHeight() {
        UiTheme theme = PipeThemes.REQUEST;
        int toolbarY = toolbarY(theme);
        int gridY = toolbarY + TOOLBAR_H + GRID_GAP;
        int actionY = gridY + StockItemGrid.VISIBLE_ROWS * StockItemGrid.SLOT + GRID_GAP;
        return actionY + ACTION_H + GuiLayout.CONTENT_BOTTOM_PAD;
    }

    private static int toolbarY(UiTheme theme) {
        return theme.headerHeight() + 3;
    }

    private int toolbarY() {
        return toolbarY(PipeThemes.REQUEST);
    }

    private int gridY() {
        return toolbarY() + TOOLBAR_H + GRID_GAP;
    }

    private int actionY() {
        return gridY() + StockItemGrid.VISIBLE_ROWS * StockItemGrid.SLOT + GRID_GAP;
    }

    @Override
    protected void init() {
        super.init();
        this.headerIcon = ScreenHeader.blockIcon(menu.pos());
        ClientRequestGui.clearResult();
        UiTheme theme = PipeThemes.REQUEST;
        int ty = toolbarY();
        int contentRight = leftPos + imageWidth - CONTENT_X;
        int groupX = contentRight - TOOL_BTN_W;
        int sortX = groupX - TOOL_GAP - TOOL_BTN_W;
        int searchW = sortX - TOOL_GAP - (leftPos + CONTENT_X);

        grid = new StockItemGrid(leftPos + CONTENT_X, topPos + gridY());

        search = UiTextBox.builder(font, Component.translatable("gui.bobbypipes.request.search"))
                .bounds(leftPos + CONTENT_X, topPos + ty, Math.max(40, searchW), TOOLBAR_H)
                .theme(theme)
                .maxLength(64)
                .responder(text -> {
                    grid.resetScroll();
                    rebuildFilter();
                })
                .build();
        addRenderableWidget(search);

        sortButton = UiButton.builder(Component.literal(sortMode.label), button -> {
                    sortMode = sortMode.next();
                    button.setMessage(BobbyFonts.literal(sortMode.label));
                    grid.resetScroll();
                    rebuildFilter();
                })
                .bounds(sortX, topPos + ty, TOOL_BTN_W, TOOLBAR_H)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.bobbypipes.request.sort")))
                .build()
                .setTheme(theme);
        addRenderableWidget(sortButton);

        groupButton = UiButton.builder(groupLabel(), button -> {
                    groupCraftable = !groupCraftable;
                    button.setMessage(BobbyFonts.apply(groupLabel()));
                    grid.resetScroll();
                    rebuildFilter();
                })
                .bounds(groupX, topPos + ty, TOOL_BTN_W, TOOLBAR_H)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.bobbypipes.request.group")))
                .build()
                .setTheme(theme);
        addRenderableWidget(groupButton);

        int ay = actionY();
        int qtyLabelW = font.width(QTY_LABEL);
        int qtyX = leftPos + CONTENT_X + qtyLabelW + 3;
        quantity = UiTextBox.builder(font, Component.translatable("gui.bobbypipes.request.quantity"))
                .bounds(qtyX, topPos + ay, QTY_W, ACTION_H)
                .theme(theme)
                .maxLength(8)
                .filter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit))
                .value("1")
                .build();
        addRenderableWidget(quantity);

        int requestX = qtyX + QTY_W + TOOL_GAP;
        int requestW = contentRight - requestX;
        requestButton = UiButton.builder(
                        Component.translatable("gui.bobbypipes.request.submit"),
                        button -> sendRequest())
                .bounds(requestX, topPos + ay, Math.max(48, requestW), ACTION_H)
                .build()
                .setTheme(theme);
        addRenderableWidget(requestButton);

        rebuildFilter();
    }

    /** Shows the shortfall list, or hides it when there is nothing missing. */
    private void openShortfallModal(List<RequestResultPayload.Shortfall> shortfalls) {
        closeShortfallModal();
        if (shortfalls.isEmpty()) {
            return;
        }
        shortfallModal = shortfalls;
        modalClose = UiButton.builder(
                        Component.translatable("gui.bobbypipes.request.close"),
                        button -> closeShortfallModal())
                .bounds(leftPos + imageWidth / 2 - 30, topPos + modalHeight() - 26, 60, 20)
                .build()
                .setTheme(PipeThemes.REQUEST);
        addRenderableWidget(modalClose);
    }

    private void closeShortfallModal() {
        if (modalClose != null) {
            removeWidget(modalClose);
            modalClose = null;
        }
        shortfallModal = null;
    }

    private int modalHeight() {
        return 34 + Math.min(shortfallModal == null ? 0 : shortfallModal.size(), 6) * 20 + 8;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        rebuildFilter();
        requestButton.active = !selected.isEmpty() && parseQuantity() > 0;

        RequestResultPayload latest = ClientRequestGui.lastResult();
        // Only pop the shortfall card when nothing shipped. A partial dispatch already
        // shows on the status line; opening the modal would interrupt spam-clicking
        // Request for another batch of whatever is left.
        if (latest != null && shortfallModal == null && latest.shipped() <= 0
                && !latest.shortfalls().isEmpty() && !shortfallShown) {
            shortfallShown = true;
            openShortfallModal(latest.shortfalls());
        }
        if (latest == null) {
            shortfallShown = false;
        }
    }

    private void rebuildFilter() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        List<NetworkStockPayload.Entry> next = new ArrayList<>();
        for (NetworkStockPayload.Entry entry : ClientRequestGui.stock()) {
            if (query.isEmpty() || matches(entry, query)) {
                next.add(entry);
            }
        }
        next.sort(comparator());
        filtered = next;
        if (grid != null) {
            grid.clampScroll(filtered.size());
        }
        if (!selected.isEmpty()) {
            boolean stillVisible = filtered.stream().anyMatch(e -> e.item().equals(selected));
            if (!stillVisible) {
                selected = ItemResource.EMPTY;
            }
        }
    }

    /**
     * Says why the grid is blank, centred in the grid rather than tucked into its corner.
     *
     * <p>The background art paints a full lattice of empty slots, so plain text laid over
     * it is close to unreadable: the cell borders run straight through the glyphs. The
     * message gets its own scrim for that reason, and sits in the middle of the empty area
     * where the eye is already looking.
     *
     * <p>A search that matched nothing is not an empty network, and saying so would send
     * the player hunting for a fault that is not there.
     */
    private void drawEmptyNotice(GuiGraphicsExtractor graphics) {
        boolean searching = search != null && !search.getValue().trim().isEmpty();
        Component message = Component.translatable(searching
                ? "gui.bobbypipes.request.no_matches"
                : "gui.bobbypipes.request.empty");
        int width = font.width(message);
        int x = leftPos + CONTENT_X + (grid.width() - width) / 2;
        int y = topPos + gridY() + (grid.height() - font.lineHeight) / 2;
        graphics.fill(x - 4, y - 3, x + width + 4, y + font.lineHeight + 2, 0xC0_10_10_10);
        graphics.text(font, message, x, y, PipeThemes.REQUEST.labelMuted(), false);
    }

    /**
     * Draws the shortfall list over a dimmed screen.
     *
     * <p>A total on its own only says the request failed. This says which items and how
     * many, so the fix is obvious without opening anything else.
     */
    private void renderShortfallModal(GuiGraphicsExtractor graphics) {
        List<RequestResultPayload.Shortfall> shortfalls = shortfallModal;
        if (shortfalls == null) {
            return;
        }
        int height = modalHeight();
        int x = leftPos;
        int y = topPos + 30;

        graphics.fill(0, 0, width, this.height, 0xA0_10_10_14);
        Panel.draw(graphics, PipeThemes.REQUEST, x, y, imageWidth, height);

        graphics.text(font, Component.translatable("gui.bobbypipes.request.missing.title"),
                x + 8, y + 8, 0xFF_FF_8A_7A, false);

        int row = y + 22;
        int shown = Math.min(shortfalls.size(), 6);
        for (int i = 0; i < shown; i++) {
            RequestResultPayload.Shortfall shortfall = shortfalls.get(i);
            graphics.item(shortfall.item(), x + 8, row);
            graphics.text(font, shortfall.item().getHoverName().getString(),
                    x + 30, row + 5, 0xFF_E6_E6_EE, false);
            String amount = "x" + shortfall.amount();
            graphics.text(font, amount,
                    x + imageWidth - 8 - font.width(amount), row + 5, 0xFF_FF_C9_6B, false);
            row += 20;
        }
        if (shortfalls.size() > shown) {
            graphics.text(font, Component.translatable("gui.bobbypipes.request.missing.more",
                            shortfalls.size() - shown),
                    x + 8, row + 5, 0xFF_9A_A0_AE, false);
        }
    }

    private Component groupLabel() {
        return Component.literal(groupCraftable ? "Grp" : "All");
    }

    /**
     * Ordering for the grid.
     *
     * <p>With grouping off, entries are sorted only by the active mode (A-Z / 1-9 / ...).
     * With grouping on, every non-craftable entry is listed first (sorted by that mode),
     * then every craftable entry (sorted the same way) - e.g. 1-9 stored, then 1-9 craft.
     */
    private java.util.Comparator<NetworkStockPayload.Entry> comparator() {
        java.util.Comparator<NetworkStockPayload.Entry> byName =
                java.util.Comparator.comparing(RequestScreen::displayName, String.CASE_INSENSITIVE_ORDER);
        java.util.Comparator<NetworkStockPayload.Entry> order = switch (sortMode) {
            case NAME_ASC -> byName;
            case NAME_DESC -> byName.reversed();
            case COUNT_DESC -> java.util.Comparator
                    .comparingInt(NetworkStockPayload.Entry::amount).reversed().thenComparing(byName);
            case COUNT_ASC -> java.util.Comparator
                    .comparingInt(NetworkStockPayload.Entry::amount).thenComparing(byName);
        };
        if (!groupCraftable) {
            return order;
        }
        return java.util.Comparator
                .comparing((NetworkStockPayload.Entry e) -> e.craftable())
                .thenComparing(order);
    }

    private static String displayName(NetworkStockPayload.Entry entry) {
        return entry.item().toStack(1).getHoverName().getString();
    }

    private static boolean matches(NetworkStockPayload.Entry entry, String query) {
        ItemStack stack = entry.item().toStack(1);
        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        String id = BuiltInRegistries.ITEM.getKey(entry.item().getItem()).toString().toLowerCase(Locale.ROOT);
        return name.contains(query) || id.contains(query);
    }

    private void sendRequest() {
        int amount = parseQuantity();
        if (selected.isEmpty() || amount <= 0) {
            return;
        }
        // A new dispatch replaces the previous shortfall card so spam-clicking Request is
        // not trapped behind the modal.
        closeShortfallModal();
        shortfallShown = false;
        ClientPacketDistributor.sendToServer(
                new RequestItemPayload(menu.pos(), selected, amount));
    }

    private int parseQuantity() {
        if (quantity == null) {
            return 0;
        }
        String text = quantity.getValue().trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(text));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** White, not vanilla's dark grey: this panel is not a stone-coloured vanilla one. */
    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ScreenHeader.draw(graphics, font, PipeThemes.REQUEST, title, headerIcon, PanelStyle.LABEL);
        graphics.text(
                font,
                QTY_LABEL,
                CONTENT_X,
                actionY() + (ACTION_H - 8) / 2,
                PipeThemes.REQUEST.labelPrimary(),
                false);
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
                StockItemGrid.COLUMNS,
                StockItemGrid.VISIBLE_ROWS,
                StockItemGrid.SLOT);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (grid != null) {
            grid.draw(graphics, font, filtered, selected, mouseX, mouseY);
            if (filtered.isEmpty()) {
                drawEmptyNotice(graphics);
            }
        }
        // Last, so it covers the panel. The close button is a normal widget and draws
        // itself; everything else here is painted over what is already on screen.
        renderShortfallModal(graphics);
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        if (grid == null) {
            return;
        }
        Optional<NetworkStockPayload.Entry> hit = grid.entryAt(filtered, mouseX, mouseY);
        if (hit.isEmpty()) {
            return;
        }
        NetworkStockPayload.Entry entry = hit.get();
        ItemStack stack = entry.item().toStack(1);
        List<Component> lines = new ArrayList<>();
        lines.add(stack.getHoverName());
        if (entry.amount() > 0) {
            lines.add(Component.translatable("gui.bobbypipes.request.stored", entry.amount()));
        }
        if (entry.craftable()) {
            lines.add(Component.translatable("gui.bobbypipes.request.craftable"));
        }
        graphics.setTooltipForNextFrame(font, lines, Optional.empty(), stack, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (shortfallModal != null) {
            // Request stays clickable so each press can dispatch another batch; only the
            // rest of the panel is locked to the shortfall card / its close button.
            if (requestButton != null && requestButton.isMouseOver(event.x(), event.y())) {
                return requestButton.mouseClicked(event, doubleClick);
            }
            return modalClose != null && modalClose.mouseClicked(event, doubleClick);
        }
        // Clicking anywhere that is not the search box drops its focus, so typing goes to
        // the game again rather than silently into a box you thought you had left.
        if (search != null && !search.isMouseOver(event.x(), event.y())) {
            search.setFocused(false);
            if (getFocused() == search) {
                setFocused(null);
            }
        }
        if (grid != null && grid.mouseClicked(event, filtered.size())) {
            return true;
        }
        if (grid != null) {
            Optional<NetworkStockPayload.Entry> hit = grid.entryAt(filtered, event.x(), event.y());
            if (hit.isPresent()) {
                NetworkStockPayload.Entry entry = hit.get();
                selected = entry.item();
                int available = entry.amount();
                int current = parseQuantity();
                if (current <= 0 || (available > 0 && current > available)) {
                    quantity.setValue(String.valueOf(available > 0 ? Math.min(available, 64) : 1));
                }
                ClientRequestGui.clearResult();
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
        if (grid != null && grid.mouseDragged(event, filtered.size())) {
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (grid != null && grid.mouseScrolled(mouseX, mouseY, scrollY, filtered.size())) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            onClose();
            return true;
        }
        if (search != null && search.keyPressed(event)) {
            return true;
        }
        if (quantity != null && quantity.keyPressed(event)) {
            return true;
        }
        if ((search != null && search.canConsumeInput())
                || (quantity != null && quantity.canConsumeInput())) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        ClientRequestGui.clear();
        super.onClose();
    }
}
