package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.block.entity.PassiveSupplierPipeBlockEntity;
import com.bobby.bobbypipes.pipes.SupplierRequests;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Accepts items the network has nowhere else to put, up to a configured target.
 *
 * <p>Where {@link SupplierPipeBlock} goes and fetches its shortfall, this one advertises it
 * and waits. Nothing is requested, so a passive supplier costs the network no planning and
 * never competes with a player's request for scarce stock: it only ever picks up items that
 * were already loose and heading for a default route.
 */
public class PassiveSupplierPipeBlock extends RoutedPipeBlock implements EntityBlock {

    public PassiveSupplierPipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PassiveSupplierPipeBlockEntity(pos, state);
    }

    @Override
    protected boolean openPipeScreen(ServerPlayer player, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof PassiveSupplierPipeBlockEntity pipe)) {
            return false;
        }
        player.openMenu(pipe, buf -> {
            buf.writeBlockPos(pos);
            SupplierRequests.STREAM_CODEC.encode(buf, pipe.requests());
            buf.writeBoolean(true);
        });
        return true;
    }
}
