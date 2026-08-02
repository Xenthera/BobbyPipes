package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.network.PipeNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A transport pipe.
 *
 * <p>Carries no state of its own yet. Its job at this stage is to be recognisable to
 * {@link PipeNetwork} when the graph is read, and to tell the network when the layout
 * around it changed.
 */
public class PipeBlock extends Block {

    public PipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
                           boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !oldState.is(this)) {
            PipeNetwork.get(serverLevel).invalidate(pos);
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos,
                                               boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        // The block is already gone from the world here, so the network reads the hole
        // and rebuilds from whatever pipes are still adjacent to it.
        PipeNetwork.get(level).invalidate(pos);
    }
}
