package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.block.entity.ProviderPipeBlockEntity;
import com.bobby.bobbypipes.network.PipeExtractRates;
import com.bobby.bobbypipes.pipes.ProviderSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A routed pipe that offers the inventories touching it to the network.
 *
 * <p>Being a provider is opt-in. A plain pipe running past a chest does not quietly start
 * handing out its contents, which is both the expected behaviour and what stops a player's
 * personal storage being drained by a network that merely passes nearby.
 *
 * <p>Send rate matches {@link PipeExtractRates}: {@link #ITEMS_PER_PULSE} items every
 * {@link #PULSE_INTERVAL_TICKS} ticks. Each pulse is one parcel - large requests drip out
 * over time rather than leaving as a single giant stack.
 */
public class ProviderPipeBlock extends RoutedPipeBlock implements EntityBlock {

    /** Items one basic provider may extract per send pulse. */
    public static final int ITEMS_PER_PULSE = PipeExtractRates.ITEMS_PER_PULSE;

    /** Ticks between send pulses (5 ticks = 1/4 second at 20 tps). */
    public static final int PULSE_INTERVAL_TICKS = PipeExtractRates.PULSE_INTERVAL_TICKS;

    public ProviderPipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ProviderPipeBlockEntity(pos, state);
    }

    @Override
    protected boolean openPipeScreen(ServerPlayer player, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ProviderPipeBlockEntity pipe)) {
            return false;
        }
        player.openMenu(pipe, buf -> {
            buf.writeBlockPos(pos);
            ProviderSettings.STREAM_CODEC.encode(buf, pipe.settings());
        });
        return true;
    }
}
