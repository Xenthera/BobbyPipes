package com.bobby.bobbypipes.block.entity;

import com.bobby.bobbypipes.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Marker BE for the autocraft monitor; the menu carries live queue state via packets. */
public class AutocraftMonitorBlockEntity extends BlockEntity {

    public AutocraftMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AUTOCRAFT_MONITOR.get(), pos, state);
    }

    public Component title() {
        return Component.translatable("menu.bobbypipes.autocraft_monitor");
    }
}
