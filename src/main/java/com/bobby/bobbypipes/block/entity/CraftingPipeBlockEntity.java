package com.bobby.bobbypipes.block.entity;

import com.bobby.bobbypipes.craft.CraftPattern;
import com.bobby.bobbypipes.menu.CraftingPipeMenu;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

public class CraftingPipeBlockEntity extends PipeBlockEntity implements MenuProvider {

    private CraftPattern pattern = CraftPattern.EMPTY;

    public CraftingPipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CRAFTING_PIPE.get(), pos, state);
    }

    public CraftPattern pattern() {
        return pattern;
    }

    /**
     * Sets the pattern exactly as given.
     *
     * <p>Deliberately does not resolve against the vanilla recipe book. A crafting pipe
     * drives whatever machine is attached to it, which may be a furnace, a modded machine,
     * or anything else, so its inputs and output are whatever the player says they are.
     * Resolving here overwrote a hand set output with the vanilla crafting result, or with
     * nothing at all when no crafting recipe matched, which made a log to charcoal pattern
     * on an attached furnace impossible to express.
     *
     * <p>The Pattern Table still resolves, because it performs a real vanilla craft and its
     * output genuinely is whatever the recipe book says.
     */
    public void setPattern(CraftPattern pattern) {
        CraftPattern next = pattern == null ? CraftPattern.EMPTY : pattern;
        this.pattern = next;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("menu.bobbypipes.crafting_pipe");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new CraftingPipeMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("pattern", CraftPattern.CODEC, pattern);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.read("pattern", CraftPattern.CODEC).ifPresent(parsed -> pattern = parsed);
    }
}
