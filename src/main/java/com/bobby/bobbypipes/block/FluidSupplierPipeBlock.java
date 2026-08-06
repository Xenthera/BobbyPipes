package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.block.entity.FluidSupplierPipeBlockEntity;
import com.bobby.bobbypipes.menu.FluidRequestMenus;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the tank touching it topped up to a configured fluid and amount by pulling the
 * shortfall from the network. The fluid equivalent of {@link EnergySupplierPipeBlock}.
 */
public class FluidSupplierPipeBlock extends FluidPipeBlock implements EntityBlock {

    public FluidSupplierPipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FluidSupplierPipeBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.FLUID_SUPPLIER_PIPE.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<FluidSupplierPipeBlockEntity>)
                FluidSupplierPipeBlockEntity::serverTick;
    }

    @Override
    protected boolean openPipeScreen(ServerPlayer player, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FluidSupplierPipeBlockEntity pipe)) {
            return false;
        }
        player.openMenu(pipe, buf -> {
            buf.writeBlockPos(pos);
            FluidResource.STREAM_CODEC.encode(buf, pipe.targetFluid());
            buf.writeVarInt(pipe.targetMb());
        });
        // The picker grid needs the same fluid catalog the Request screen browses, this
        // pipe just is not itself a Request menu so nothing else pushes it automatically.
        FluidRequestMenus.syncStock(player, pos);
        return true;
    }
}
