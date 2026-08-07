package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * A pipe that participates in routing decisions.
 *
 * <p>Classic Logistics Pipes split the network into unrouted transport tubes and routed
 * pipes (Basic, Provider, Request, ...). Only routed pipes are routers: they discover each
 * other across unbranched corridors of plain pipe, own green/red exit marks, and are the
 * nodes of the routing graph. Plain {@link PipeBlock}s are corridor fabric only.
 *
 * <p>Configuration screens open with the Wrench rather than a bare right click. Pipes sit
 * against chests and machines, so a bare click is far more often an attempt to open the
 * container behind them; requiring the tool keeps that from being hijacked. The Request
 * pipe is the exception, since it is used constantly and holds no configuration.
 *
 * <p>When a click opens a pipe screen, it consumes the interaction so the held item does
 * not also place (buckets, blocks, ...). Sneak to use the held item on/against the pipe.
 *
 * <p>{@link #POWERED} drives the centre-body green/red logistics-power overlay.
 */
public class RoutedPipeBlock extends PipeBlock {

    public static final BooleanProperty POWERED = BooleanProperty.create("powered");

    public RoutedPipeBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(POWERED, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(POWERED);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state.setValue(POWERED, true);
    }

    /**
     * Opens this pipe's screen.
     *
     * @return true if this pipe has a screen and it was opened
     */
    protected boolean openPipeScreen(ServerPlayer player, Level level, BlockPos pos) {
        return false;
    }

    /** Whether a bare right click opens the screen, instead of needing the Wrench. */
    protected boolean opensWithoutWrench() {
        return false;
    }

    public static boolean isWrench(ItemStack stack) {
        return stack.is(ModItems.WRENCH.get());
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                          BlockPos pos, Player player, InteractionHand hand,
                                          BlockHitResult hitResult) {
        // Sneak keeps the held item's normal use (bucket waterlogging, placing against the
        // pipe, ...). Without that escape hatch every click would be stolen by the UI.
        if (player.isSecondaryUseActive()) {
            return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
        }
        // Request pipes open on any bare click; the wrench opens every routed pipe. Either
        // way the click belongs to the pipe, not the held item - otherwise a water bucket
        // places fluid and only then opens the requester UI.
        if (isWrench(stack) || opensWithoutWrench()) {
            if (player instanceof ServerPlayer serverPlayer) {
                openPipeScreen(serverPlayer, level, pos);
            }
            return InteractionResult.SUCCESS;
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (opensWithoutWrench() && player instanceof ServerPlayer serverPlayer) {
            openPipeScreen(serverPlayer, level, pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
}
