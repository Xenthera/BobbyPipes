package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.entity.PassiveSupplierPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.StockTargetPipeBlockEntity;
import com.bobby.bobbypipes.pipes.SupplierRequests;
import com.bobby.bobbypipes.registry.ModMenus;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class SupplierPipeMenu extends AbstractContainerMenu {

    /**
     * First player-inventory item row. Kept tight under the 18px target row
     * ({@code 20 + CONTENT_TOP_PAD}) with a 22px label/breathing gap.
     */
    public static final int INV_SLOT_Y = 20 + GuiLayout.CONTENT_TOP_PAD + 18 + 22;
    /** Vanilla player inv block: 3 rows + hotbar gap + hotbar, plus bottom pad. */
    public static final int PANEL_HEIGHT = INV_SLOT_Y + (3 * 18 + 4 + 18) + GuiLayout.CONTENT_BOTTOM_PAD + 2;

    private final BlockPos pos;
    /** Which of the two stock-target pipes opened this, so the screen can dress itself. */
    private final boolean passive;
    private SupplierRequests requests;

    public SupplierPipeMenu(int id, Inventory inventory, StockTargetPipeBlockEntity pipe) {
        super(ModMenus.SUPPLIER_PIPE.get(), id);
        this.pos = pipe.getBlockPos().immutable();
        this.passive = pipe instanceof PassiveSupplierPipeBlockEntity;
        this.requests = pipe.requests();
        addStandardInventorySlots(inventory, GuiLayout.playerInventoryOriginX(), INV_SLOT_Y);
    }

    public SupplierPipeMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModMenus.SUPPLIER_PIPE.get(), id);
        this.pos = buf.readBlockPos();
        this.requests = SupplierRequests.STREAM_CODEC.decode(buf);
        this.passive = buf.readBoolean();
        addStandardInventorySlots(inventory, GuiLayout.playerInventoryOriginX(), INV_SLOT_Y);
    }

    public BlockPos pos() {
        return pos;
    }

    public boolean passive() {
        return passive;
    }

    public SupplierRequests requests() {
        return requests;
    }

    public void setRequestsLocal(SupplierRequests requests) {
        this.requests = requests;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isWithinBlockInteractionRange(pos, 4.0);
    }
}
