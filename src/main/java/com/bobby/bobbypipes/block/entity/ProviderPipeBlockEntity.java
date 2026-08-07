package com.bobby.bobbypipes.block.entity;

import com.bobby.bobbypipes.menu.ProviderPipeMenu;
import com.bobby.bobbypipes.pipes.ProviderSettings;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Settings holder for a provider pipe: item filter, include/exclude, and leave mode.
 */
public class ProviderPipeBlockEntity extends PipeBlockEntity implements MenuProvider {

    private ProviderSettings settings = ProviderSettings.EMPTY;

    public ProviderPipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PROVIDER_PIPE.get(), pos, state);
    }

    public ProviderSettings settings() {
        return settings;
    }

    public void setSettings(ProviderSettings settings) {
        this.settings = settings == null ? ProviderSettings.EMPTY : settings;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("menu.bobbypipes.provider_pipe");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ProviderPipeMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("settings", ProviderSettings.CODEC, settings);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        settings = input.read("settings", ProviderSettings.CODEC).orElse(ProviderSettings.EMPTY);
    }
}
