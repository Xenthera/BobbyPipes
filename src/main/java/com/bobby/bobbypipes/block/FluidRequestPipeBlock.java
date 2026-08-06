package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.menu.FluidRequestMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * A routed pipe that requests fluid from the network and delivers it into the tank
 * touching it.
 */
public class FluidRequestPipeBlock extends FluidPipeBlock {

    public FluidRequestPipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected boolean openPipeScreen(ServerPlayer player, Level level, BlockPos pos) {
        FluidRequestMenus.open(player, pos);
        return true;
    }

    /** Same reasoning as the item and energy Request pipes: used constantly, no configuration. */
    @Override
    protected boolean opensWithoutWrench() {
        return true;
    }
}
