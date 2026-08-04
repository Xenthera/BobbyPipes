package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

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
 */
public class RoutedPipeBlock extends PipeBlock {

    public RoutedPipeBlock(Properties properties) {
        super(properties);
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
        if (!isWrench(stack)) {
            return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            openPipeScreen(serverPlayer, level, pos);
        }
        // Consume on both sides so the held wrench does not also try to place something.
        return InteractionResult.SUCCESS;
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
