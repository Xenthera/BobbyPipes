package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.block.entity.AutocraftMonitorBlockEntity;
import com.bobby.bobbypipes.menu.AutocraftMonitorMenus;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Network terminal for the autocraft queue.
 *
 * <p>Must sit next to a routed (smart) pipe. Opens a scrollable card UI of pending crafts
 * with soft/hard timeout status for that pipe's network component.
 */
public class AutocraftMonitorBlock extends BaseEntityBlock {

    public static final MapCodec<AutocraftMonitorBlock> CODEC = simpleCodec(AutocraftMonitorBlock::new);

    public AutocraftMonitorBlock(Properties properties) {
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
        return new AutocraftMonitorBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer) {
            AutocraftMonitorMenus.open(serverPlayer, pos);
        }
        return InteractionResult.SUCCESS;
    }
}
