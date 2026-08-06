package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.block.entity.BasicPipeBlockEntity;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A routed pipe with no inventory role - the Logistics Pipes "Basic" pipe.
 *
 * <p>Place these at junctions of transport pipe so the router graph stays connected.
 * Optionally mark one as a default route (GUI checkbox) so excess items prefer its
 * attached inventory when that inventory has space.
 */
public class BasicPipeBlock extends RoutedPipeBlock implements EntityBlock {

    public BasicPipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BasicPipeBlockEntity(pos, state);
    }

    /**
     * Drains anything pushed into this pipe onto the network.
     *
     * <p>Server side only; the client has no network state to route against.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.BASIC_PIPE.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<BasicPipeBlockEntity>)
                BasicPipeBlockEntity::serverTick;
    }

    @Override
    protected boolean openPipeScreen(ServerPlayer player, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof BasicPipeBlockEntity pipe)) {
            return false;
        }
        player.openMenu(pipe, buf -> {
            buf.writeBlockPos(pos);
            buf.writeBoolean(pipe.isDefaultRoute());
        });
        return true;
    }
}
