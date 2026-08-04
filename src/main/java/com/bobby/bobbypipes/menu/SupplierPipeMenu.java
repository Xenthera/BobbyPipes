package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.entity.PassiveSupplierPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.StockTargetPipeBlockEntity;
import com.bobby.bobbypipes.pipes.SupplierRequests;
import com.bobby.bobbypipes.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class SupplierPipeMenu extends AbstractContainerMenu {

    private final BlockPos pos;
    /** Which of the two stock-target pipes opened this, so the screen can dress itself. */
    private final boolean passive;
    private SupplierRequests requests;

    public SupplierPipeMenu(int id, Inventory inventory, StockTargetPipeBlockEntity pipe) {
        super(ModMenus.SUPPLIER_PIPE.get(), id);
        this.pos = pipe.getBlockPos().immutable();
        this.passive = pipe instanceof PassiveSupplierPipeBlockEntity;
        this.requests = pipe.requests();
        addStandardInventorySlots(inventory, 9, 85);
    }

    public SupplierPipeMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModMenus.SUPPLIER_PIPE.get(), id);
        this.pos = buf.readBlockPos();
        this.requests = SupplierRequests.STREAM_CODEC.decode(buf);
        this.passive = buf.readBoolean();
        addStandardInventorySlots(inventory, 9, 85);
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
