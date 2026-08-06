package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.menu.EnergyRequestMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * A routed pipe that requests FE from the network and delivers it into the energy
 * storage touching it.
 */
public class EnergyRequestPipeBlock extends EnergyPipeBlock {

    public EnergyRequestPipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected boolean openPipeScreen(ServerPlayer player, Level level, BlockPos pos) {
        EnergyRequestMenus.open(player, pos);
        return true;
    }

    /** Same reasoning as the item Request pipe: used constantly, holds no configuration. */
    @Override
    protected boolean opensWithoutWrench() {
        return true;
    }
}
