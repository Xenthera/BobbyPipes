package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.block.entity.PassiveSupplierPipeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.Optional;

/**
 * Where an item with no destination should go.
 *
 * <p>Items reach this in three ways: pushed into a pipe by a hopper, drifting out of plain
 * pipe onto a router, or left over when a craft yields more than was asked for. None of
 * them carry a destination, so something has to choose one.
 *
 * <p>The choice is by priority, then by distance, matching how Logistics Pipes ranks its
 * sinks. A passive supplier that is still short of its target wins, because it asked for
 * this item specifically and a default route did not ask for anything. Only when no passive
 * supplier wants it does it fall through to the nearest default route, which is the catch
 * all. Within a tier the cheapest route wins, so items spend the least time in transit.
 *
 * <p>A passive supplier takes only what it is short by. The rest is offered onward the next
 * time it needs a home rather than being dumped past the target, which is what makes a
 * target of 5 mean five and not "the next stack that happens by".
 */
public final class SinkFinder {

    /**
     * Somewhere to put an item, and how much of it that place will take.
     *
     * @param pos    the destination pipe. May equal the origin, in which case the caller
     *               should insert locally rather than route a parcel to itself
     * @param accept how many of the offered items to send, never more than was offered
     */
    public record Sink(BlockPos pos, int accept) {
    }

    private SinkFinder() {
    }

    /**
     * Picks a home for {@code count} of {@code item} sitting at {@code from}.
     *
     * @return empty when nothing on the network will take any of it
     */
    public static Optional<Sink> nearest(ServerLevel level,
                                         PipeNetwork network,
                                         BlockPos from,
                                         ItemResource item,
                                         int count) {
        if (item.isEmpty() || count <= 0) {
            return Optional.empty();
        }
        Optional<Sink> demanded = nearestDemand(level, network, from, item, count);
        if (demanded.isPresent()) {
            return demanded;
        }
        return DefaultRouteFinder.nearestWithSpace(level, network, from, item)
                .map(pos -> new Sink(pos, count));
    }

    /** The cheapest passive supplier still short of {@code item}, if any. */
    private static Optional<Sink> nearestDemand(ServerLevel level,
                                                PipeNetwork network,
                                                BlockPos from,
                                                ItemResource item,
                                                int count) {
        // A passive supplier attached to the pipe holding the item takes it without a
        // parcel, the same shortcut the default route finder makes for itself.
        int here = shortfall(level, network, from, item);
        if (here > 0) {
            return Optional.of(new Sink(from, Math.min(count, here)));
        }
        return network.routes().routesFrom(from).flatMap(table -> {
            for (BlockPos dest : table.destinationsByCost()) {
                if (dest.equals(from)) {
                    continue;
                }
                int need = shortfall(level, network, dest, item);
                if (need > 0) {
                    return Optional.of(new Sink(dest, Math.min(count, need)));
                }
            }
            return Optional.empty();
        });
    }

    /**
     * How many more of {@code item} the passive supplier at {@code pos} wants right now.
     *
     * <p>Counts what is already on its way as if it had arrived. Without that, every sink
     * lookup in the seconds a parcel spends in transit would see the same shortfall and
     * send a second lot, so a target of 5 would fill to 5 several times over.
     *
     * @return 0 if there is no passive supplier there, it is at target, or it has no room
     */
    private static int shortfall(ServerLevel level, PipeNetwork network, BlockPos pos,
                                 ItemResource item) {
        if (!level.hasChunkAt(pos)
                || !(level.getBlockEntity(pos) instanceof PassiveSupplierPipeBlockEntity pipe)) {
            return 0;
        }
        int target = pipe.targetFor(item);
        if (target <= 0) {
            return 0;
        }
        int need = target - InventoryAccess.count(level, pos, item) - network.inboundTo(pos, item);
        if (need <= 0) {
            return 0;
        }
        return InventoryAccess.insertable(level, pos, item, need);
    }
}
