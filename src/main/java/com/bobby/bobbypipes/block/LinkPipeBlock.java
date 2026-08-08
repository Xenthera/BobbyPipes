package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.block.entity.LinkPipeBlockEntity;
import com.bobby.bobbypipes.logistics.LinkPipeRegistry;
import com.bobby.bobbypipes.logistics.PipeNodeId;
import com.bobby.bobbypipes.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Corridor fabric that pairs with exactly one other link pipe on the same channel.
 *
 * <p>Not a router ({@link RoutedPipeBlock}): DirectCorridors walks through it like plain
 * pipe. The channel pair inserts a cost-1 virtual lattice edge so both sides of the
 * wormhole see each other as adjacent dumb pipe.
 *
 * <p>{@link #LINK} drives the body overlay (idle / waiting / severed / live), composited
 * the same way arm marks use direct/indirect textures.
 */
public class LinkPipeBlock extends PipeBlock implements EntityBlock {

    public static final EnumProperty<LinkStatus> LINK = EnumProperty.create("link", LinkStatus.class);

    public LinkPipeBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(LINK, LinkStatus.IDLE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(LINK);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return super.getStateForPlacement(context).setValue(LINK, LinkStatus.IDLE);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LinkPipeBlockEntity(pos, state);
    }

    /**
     * Recomputes {@link #LINK} from the registry without releasing the channel claim.
     */
    public static void refreshLinkStatus(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LinkPipeBlock)) {
            return;
        }
        LinkStatus desired = statusOf(level, pos);
        if (state.getValue(LINK) != desired) {
            level.setBlock(pos, state.setValue(LINK, desired), 3);
        }
    }

    public static LinkStatus statusOf(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof LinkPipeBlockEntity be) || be.channel() <= 0) {
            return LinkStatus.IDLE;
        }
        LinkPipeRegistry registry = LinkPipeRegistry.get(level);
        PipeNodeId self = PipeNodeId.of(level, pos);
        if (registry.peerOf(self).isEmpty()) {
            return LinkStatus.WAITING;
        }
        return registry.isLive(self) ? LinkStatus.LIVE : LinkStatus.SEVERED;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                          BlockPos pos, Player player, InteractionHand hand,
                                          BlockHitResult hitResult) {
        if (player.isSecondaryUseActive()) {
            return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
        }
        if (!stack.is(ModItems.WRENCH.get())) {
            return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof LinkPipeBlockEntity pipe) {
            serverPlayer.openMenu(pipe, buf -> {
                buf.writeBlockPos(pos);
                buf.writeVarInt(pipe.channel());
                buf.writeBoolean(pipe.isPaired());
                buf.writeBoolean(pipe.isLive());
            });
        }
        return InteractionResult.SUCCESS;
    }
}
