package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.block.entity.PowerJunctionBlockEntity;
import com.bobby.bobbypipes.logistics.PipeNetwork;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Logistics power supply. Accepts FE from any cable and pays for routing actions on the
 * adjacent pipe component.
 */
public class PowerJunctionBlock extends BaseEntityBlock {

    public static final MapCodec<PowerJunctionBlock> CODEC = simpleCodec(PowerJunctionBlock::new);

    public PowerJunctionBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PowerJunctionBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide()
                ? null
                : createTickerHelper(type, com.bobby.bobbypipes.registry.ModBlockEntities.POWER_JUNCTION.get(),
                        PowerJunctionBlock::serverTick);
    }

    private static void serverTick(Level level, BlockPos pos, BlockState state,
                                   PowerJunctionBlockEntity junction) {
        junction.beginTickMeters();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof PowerJunctionBlockEntity junction) {
            serverPlayer.openMenu(junction, buf -> {
                buf.writeBlockPos(pos);
                buf.writeVarInt(junction.energy());
                buf.writeVarInt(junction.capacity());
            });
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
                           boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            invalidateAdjacentPipes(serverLevel, pos);
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state,
                                               ServerLevel level,
                                               BlockPos pos, boolean movedByPiston) {
        invalidateAdjacentPipes(level, pos);
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
    }

    private static void invalidateAdjacentPipes(ServerLevel level, BlockPos pos) {
        PipeNetwork network = PipeNetwork.get(level);
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.relative(direction);
            if (level.getBlockState(neighbour).getBlock() instanceof PipeBlock) {
                network.invalidate(neighbour);
            }
        }
    }
}
