package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.block.entity.FuelGeneratorBlockEntity;
import com.bobby.bobbypipes.menu.GeneratorMenus;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Burns furnace fuel into FE for the logistics network.
 *
 * <p>Right-click always opens the screen, including with fuel in hand. An earlier version
 * quietly loaded held fuel instead, which meant the natural first thing a player does -
 * right-click holding coal - swallowed the interaction and made the screen look absent.
 */
public class FuelGeneratorBlock extends BaseEntityBlock {

    public static final MapCodec<FuelGeneratorBlock> CODEC = simpleCodec(FuelGeneratorBlock::new);
    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    /** Which way the decorated front face points; the rest of the block is plain casing. */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public FuelGeneratorBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
                .setValue(LIT, false)
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(LIT, FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Furnace convention: the front faces the player who placed it.
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FuelGeneratorBlockEntity(pos, state);
    }

    @Override
    public @Nullable <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide()
                ? null
                : createTickerHelper(type, ModBlockEntities.FUEL_GENERATOR.get(),
                        FuelGeneratorBlock::serverTick);
    }

    private static void serverTick(Level level, BlockPos pos, BlockState state,
                                   FuelGeneratorBlockEntity generator) {
        if (generator.serverTick(level, pos)) {
            level.setBlock(pos, state.setValue(LIT, generator.isBurning()),
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel
                && level.getGameTime() % GeneratorMenus.SYNC_INTERVAL_TICKS == 0) {
            GeneratorMenus.sync(serverLevel, pos, generator);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof FuelGeneratorBlockEntity generator) {
            GeneratorMenus.openFuel(serverPlayer, pos, generator);
        }
        return InteractionResult.SUCCESS;
    }

}
