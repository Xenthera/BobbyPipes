package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.entity.CraftingPipeBlockEntity;
import com.bobby.bobbypipes.craft.CraftPattern;
import com.bobby.bobbypipes.registry.ModMenus;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class CraftingPipeMenu extends AbstractContainerMenu {

    private final BlockPos pos;
    private CraftPattern pattern;

    public CraftingPipeMenu(int id, Inventory inventory, CraftingPipeBlockEntity pipe) {
        super(ModMenus.CRAFTING_PIPE.get(), id);
        this.pos = pipe.getBlockPos().immutable();
        this.pattern = pipe.pattern();
        addStandardInventorySlots(inventory, GuiLayout.playerInventoryOriginX(), 100 + GuiLayout.CONTENT_TOP_PAD);
    }

    public CraftingPipeMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModMenus.CRAFTING_PIPE.get(), id);
        this.pos = buf.readBlockPos();
        this.pattern = CraftPattern.STREAM_CODEC.decode(buf);
        addStandardInventorySlots(inventory, GuiLayout.playerInventoryOriginX(), 100 + GuiLayout.CONTENT_TOP_PAD);
    }

    public BlockPos pos() {
        return pos;
    }

    public CraftPattern pattern() {
        return pattern;
    }

    public void setPatternLocal(CraftPattern pattern) {
        this.pattern = pattern;
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
