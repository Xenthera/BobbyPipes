package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.entity.PatternTableBlockEntity;
import com.bobby.bobbypipes.craft.CraftPattern;
import com.bobby.bobbypipes.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;

public class PatternTableMenu extends AbstractContainerMenu {

    /** Slot index of the real craft output (after the 18 resource slots). */
    public static final int OUTPUT_SLOT = PatternTableBlockEntity.RESOURCE_SLOTS;

    private final BlockPos pos;
    private CraftPattern pattern;
    private final ItemStacksResourceHandler resources;
    private final ItemStacksResourceHandler output;

    public PatternTableMenu(int id, Inventory inventory, PatternTableBlockEntity table) {
        super(ModMenus.PATTERN_TABLE.get(), id);
        this.pos = table.getBlockPos().immutable();
        this.pattern = asShaped(table.pattern());
        this.resources = table.resources();
        this.output = table.output();
        addTableSlots();
        // +1,+1 matches painted wells on our GUI texture (item sits in the inner 16x16).
        addStandardInventorySlots(inventory, 9, 141);
    }

    public PatternTableMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModMenus.PATTERN_TABLE.get(), id);
        this.pos = buf.readBlockPos();
        this.pattern = asShaped(CraftPattern.STREAM_CODEC.decode(buf));
        this.resources = new ItemStacksResourceHandler(PatternTableBlockEntity.RESOURCE_SLOTS);
        this.output = new ItemStacksResourceHandler(PatternTableBlockEntity.OUTPUT_SLOTS);
        addTableSlots();
        addStandardInventorySlots(inventory, 9, 141);
    }

    private void addTableSlots() {
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 9; col++) {
                int index = row * 9 + col;
                addSlot(new ResourceHandlerSlot(
                        resources, resources::set, index,
                        9 + col * 18, 91 + row * 18));
            }
        }
        // Crafted output only  -  players may take, not insert.
        // +1,+1 matches painted wells on our GUI texture (item sits in the inner 16x16).
        addSlot(new ResourceHandlerSlot(output, output::set, 0, 125, 36) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });
    }

    public BlockPos pos() {
        return pos;
    }

    public CraftPattern pattern() {
        return pattern;
    }

    public void setPatternLocal(CraftPattern pattern) {
        this.pattern = asShaped(pattern);
    }

    /** True when the real output slot is empty (ghost recipe preview should show). */
    public boolean isOutputEmpty() {
        return !slots.get(OUTPUT_SLOT).hasItem();
    }

    private static CraftPattern asShaped(CraftPattern pattern) {
        if (pattern.kind() == CraftPattern.Kind.SHAPED && pattern.inputs().size() == 9) {
            return pattern;
        }
        return CraftPattern.shaped(pattern.inputs(), pattern.primaryOutput(), pattern.satellite());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack remaining = stack.copy();
        int tableSlots = PatternTableBlockEntity.RESOURCE_SLOTS + PatternTableBlockEntity.OUTPUT_SLOTS;
        if (index < tableSlots) {
            // Output or resources -> player inventory.
            if (!moveItemStackTo(stack, tableSlots, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, PatternTableBlockEntity.RESOURCE_SLOTS, false)) {
            // Player -> resources only (never into output).
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return remaining;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isWithinBlockInteractionRange(pos, 4.0);
    }
}
