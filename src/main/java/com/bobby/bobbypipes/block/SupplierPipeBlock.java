package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.block.entity.SupplierPipeBlockEntity;
import com.bobby.bobbypipes.pipes.SupplierRequests;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Actively restocks its attached inventory from the network (Logistics Pipes Supplier).
 *
 * <p>Opposite of {@link ProviderPipeBlock}: providers expose stock; suppliers pull stock
 * in to keep configured targets filled. Quiet when already at target.
 */
public class SupplierPipeBlock extends RoutedPipeBlock implements EntityBlock {

    public SupplierPipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SupplierPipeBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.SUPPLIER_PIPE.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<SupplierPipeBlockEntity>)
                SupplierPipeBlockEntity::serverTick;
    }

    @Override
    protected boolean openPipeScreen(ServerPlayer player, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof SupplierPipeBlockEntity pipe)) {
            return false;
        }
        player.openMenu(pipe, buf -> {
            buf.writeBlockPos(pos);
            SupplierRequests.STREAM_CODEC.encode(buf, pipe.requests());
            buf.writeBoolean(false);
        });
        return true;
    }
}
