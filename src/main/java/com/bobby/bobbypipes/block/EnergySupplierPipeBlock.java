package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.block.entity.EnergySupplierPipeBlockEntity;
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
 * Keeps the energy storage touching it topped up to a configured FE target by pulling the
 * shortfall from the network. The energy equivalent of {@link SupplierPipeBlock}.
 */
public class EnergySupplierPipeBlock extends EnergyPipeBlock implements EntityBlock {

    public EnergySupplierPipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EnergySupplierPipeBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != ModBlockEntities.ENERGY_SUPPLIER_PIPE.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<EnergySupplierPipeBlockEntity>)
                EnergySupplierPipeBlockEntity::serverTick;
    }

    @Override
    protected boolean openPipeScreen(ServerPlayer player, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof EnergySupplierPipeBlockEntity pipe)) {
            return false;
        }
        player.openMenu(pipe, buf -> {
            buf.writeBlockPos(pos);
            buf.writeVarInt(pipe.targetFe());
        });
        return true;
    }
}
