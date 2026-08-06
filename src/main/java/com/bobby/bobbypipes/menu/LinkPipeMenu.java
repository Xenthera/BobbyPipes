package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.entity.LinkPipeBlockEntity;
import com.bobby.bobbypipes.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class LinkPipeMenu extends AbstractContainerMenu {

    private final BlockPos pos;
    private int channel;
    private boolean paired;
    /** Both ends loaded; false while paired but peer chunk is unloaded. */
    private boolean live;

    public LinkPipeMenu(int id, Inventory inventory, LinkPipeBlockEntity pipe) {
        super(ModMenus.LINK_PIPE.get(), id);
        this.pos = pipe.getBlockPos().immutable();
        this.channel = pipe.channel();
        this.paired = pipe.isPaired();
        this.live = pipe.isLive();
    }

    public LinkPipeMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModMenus.LINK_PIPE.get(), id);
        this.pos = buf.readBlockPos();
        this.channel = buf.readVarInt();
        this.paired = buf.readBoolean();
        this.live = buf.readBoolean();
    }

    public BlockPos pos() {
        return pos;
    }

    public int channel() {
        return channel;
    }

    public boolean paired() {
        return paired;
    }

    public boolean live() {
        return live;
    }

    public void setChannelLocal(int channel, boolean paired, boolean live) {
        this.channel = channel;
        this.paired = paired;
        this.live = live;
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
