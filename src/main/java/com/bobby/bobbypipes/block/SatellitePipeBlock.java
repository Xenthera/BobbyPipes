package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.block.entity.SatellitePipeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Routed pipe that names an adjacent inventory as a crafting satellite buffer.
 */
public class SatellitePipeBlock extends RoutedPipeBlock implements EntityBlock {

    public SatellitePipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SatellitePipeBlockEntity(pos, state);
    }

    @Override
    protected boolean openPipeScreen(ServerPlayer player, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof SatellitePipeBlockEntity pipe)) {
            return false;
        }
        player.openMenu(pipe, buf -> {
            buf.writeBlockPos(pos);
            buf.writeUtf(pipe.satelliteName());
        });
        return true;
    }
}
