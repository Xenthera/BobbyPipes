package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.client.ClientRequestGui;
import com.bobby.bobbypipes.menu.RequestMenu;
import com.bobby.bobbypipes.network.payload.NetworkStockPayload;
import com.bobby.bobbypipes.network.payload.RequestItemPayload;
import com.bobby.bobbypipes.network.payload.RequestResultPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
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
 * <p>Layout mirrors Refined Storage's Grid: search, 9-wide scrollable item grid with
 * creative-style scrollbar, quantity + request controls.
 */
public class RequestScreen extends AbstractContainerScreen<RequestMenu> {

    private static final int GRID_X = 8;
    private static final int GRID_Y = 35;

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
    private Button modalClose;
    /** Stops the modal reopening every tick while the same result is on screen. */
    private boolean shortfallShown;
    private SortMode sortMode = SortMode.NAME_ASC;
    private boolean groupCraftable = true;
    private Button sortButton;
    private Button groupButton;
    private EditBox search;
    private EditBox quantity;
    private Button requestButton;
    private StockItemGrid grid;
    private ItemResource selected = ItemResource.EMPTY;
    private List<NetworkStockPayload.Entry> filtered = List.of();

    public RequestScreen(RequestMenu menu, Inventory inventory, Component title) {
        // Wider than a chest so the 9-col grid + creative scrollbar fit (same idea as RS Grid).
        super(menu, inventory, title, 193, 210);
        this.inventoryLabelY = 1000;
    }

    @Override
    protected void init() {
        super.init();
        ClientRequestGui.clearResult();
        grid = new StockItemGrid(leftPos + GRID_X, topPos + GRID_Y);

        search = new EditBox(font, leftPos + 8, topPos + 18, 160, 12,
                Component.translatable("gui.bobbypipes.request.search"));
        search.setMaxLength(64);
        search.setResponder(text -> {
            grid.resetScroll();
            rebuildFilter();
        });
        addRenderableWidget(search);

        sortButton = Button.builder(Component.literal(sortMode.label), button -> {
                    sortMode = sortMode.next();
                    button.setMessage(Component.literal(sortMode.label));
                    grid.resetScroll();
                    rebuildFilter();
                })
                .bounds(leftPos - 24, topPos + 18, 22, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.bobbypipes.request.sort")))
                .build();
        addRenderableWidget(sortButton);

        groupButton = Button.builder(groupLabel(), button -> {
                    groupCraftable = !groupCraftable;
                    button.setMessage(groupLabel());
                    grid.resetScroll();
                    rebuildFilter();
                })
                .bounds(leftPos - 24, topPos + 40, 22, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("gui.bobbypipes.request.group")))
                .build();
        addRenderableWidget(groupButton);

        quantity = new EditBox(font, leftPos + 8, topPos + 150, 40, 12,
                Component.translatable("gui.bobbypipes.request.quantity"));
        quantity.setMaxLength(8);
        quantity.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        quantity.setValue("1");
        addRenderableWidget(quantity);

        requestButton = Button.builder(
                        Component.translatable("gui.bobbypipes.request.submit"),
                        button -> sendRequest())
                .bounds(leftPos + 56, topPos + 146, 129, 20)
                .build();
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
        modalClose = Button.builder(
                        Component.translatable("gui.bobbypipes.request.close"),
                        button -> closeShortfallModal())
                .bounds(leftPos + imageWidth / 2 - 30, topPos + modalHeight() - 26, 60, 20)
                .build();
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
        if (latest != null && shortfallModal == null && !latest.shortfalls().isEmpty()
                && !shortfallShown) {
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
        int x = leftPos + GRID_X + (grid.width() - width) / 2;
        int y = topPos + GRID_Y + (grid.height() - font.lineHeight) / 2;
        graphics.fill(x - 4, y - 3, x + width + 4, y + font.lineHeight + 2, 0xC0_10_10_10);
        graphics.text(font, message, x, y, 0xFF_E0_E0_E0, false);
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
        graphics.fill(x - 2, y - 2, x + imageWidth + 2, y + height + 2, 0xFF_2B_2B_33);
        graphics.fill(x, y, x + imageWidth, y + height, 0xFF_14_14_1A);

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
     * <p>With grouping off, entries are sorted only by the active mode (A-Z / 1-9 / …).
     * With grouping on, every non-craftable entry is listed first (sorted by that mode),
     * then every craftable entry (sorted the same way)  -  e.g. 1-9 stored, then 1-9 craft.
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
        graphics.text(font, title, titleLabelX, titleLabelY, PanelStyle.LABEL, false);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                ModGuiTextures.REQUEST,
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
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (grid != null) {
            grid.draw(graphics, font, filtered, selected, mouseX, mouseY);
            if (filtered.isEmpty()) {
                drawEmptyNotice(graphics);
            }
        }
        drawStatus(graphics);
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

    private void drawStatus(GuiGraphicsExtractor graphics) {
        RequestResultPayload result = ClientRequestGui.lastResult();
        Component line;
        int color = 0xFF_C0_C0_C0;
        if (result == null) {
            if (selected.isEmpty()) {
                return;
            }
            line = selected.getHoverName();
        } else if (!result.hasPipe()) {
            line = Component.translatable("gui.bobbypipes.request.no_pipe");
            color = 0xFF_FF_55_55;
        } else if (result.shipped() <= 0 && result.missing() > 0) {
            line = Component.translatable("gui.bobbypipes.request.missing", result.missing());
            color = 0xFF_FF_55_55;
        } else if (result.missing() > 0) {
            line = Component.translatable(
                    "gui.bobbypipes.request.partial", result.shipped(), result.requested());
            color = 0xFF_FF_AA_00;
        } else {
            line = Component.translatable(
                    "gui.bobbypipes.request.shipped", result.shipped(), result.requested());
            color = 0xFF_55_FF_55;
        }
        graphics.text(font, line, leftPos + 8, topPos + 172, color, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (shortfallModal != null) {
            // Modal owns input: only its close button responds, so a stray click cannot
            // fire another request through the panel behind it.
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
            grid.mouseReleased();
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
