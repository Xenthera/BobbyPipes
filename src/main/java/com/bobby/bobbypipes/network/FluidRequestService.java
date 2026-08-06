package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.block.FluidProviderPipeBlock;
import com.bobby.bobbypipes.transit.FluidShipment;
import com.bobby.bobbypipes.transit.ParcelTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moves fluid from one pipe to another as fluid parcels.
 *
 * <p>Mirrors {@link EnergyRequestService}'s push/deliver/strand/request shape, with a real
 * resource identity like items rather than energy's single implicit kind. No crafting
 * integration (fluid is never made from other fluid on this network) and no
 * drop-on-the-ground fallback, same as energy: a packet that cannot be delivered is voided
 * and its promise settled short.
 */
public final class FluidRequestService {

    /** How long a fluid promise may sit unfulfilled before it is released. */
    static final int PROMISE_TIMEOUT_TICKS = 20 * 60;

    /**
     * mB one fluid parcel carries, the same role a stack's 64-item cap plays for items. A
     * bucket, to start; tune later.
     */
    public static final int PACKET_SIZE_MB = 1000;

    private FluidRequestService() {
    }

    /**
     * Sends {@code amountMb} of {@code fluid} from {@code from} toward {@code dest} as one
     * fluid parcel.
     *
     * @return how much was actually enqueued, either {@code amountMb} or 0 if {@code dest}
     *         is not reachable from {@code from} right now
     */
    public static int pushFromPipe(ServerLevel level,
                                   PipeNetwork network,
                                   BlockPos from,
                                   BlockPos dest,
                                   FluidResource fluid,
                                   int amountMb) {
        if (fluid.isEmpty() || amountMb <= 0 || from.equals(dest)) {
            return 0;
        }
        long promiseId = network.fluidLedger().promise(from, dest, fluid, amountMb,
                level.getGameTime() + PROMISE_TIMEOUT_TICKS);
        FluidShipment shipment = new FluidShipment(fluid, amountMb, promiseId);
        if (network.fluidParcels().inject(shipment, from, dest, network.routes()).isPresent()) {
            return amountMb;
        }
        network.fluidLedger().cancel(promiseId);
        return 0;
    }

    /**
     * Applies one tick's worth of arrivals and failures.
     *
     * <p>A delivered packet settles its promise and fills the destination's tank. If the
     * tank cannot take all of it the remainder is voided rather than held or dropped.
     */
    public static void handle(ServerLevel level,
                              PipeNetwork network,
                              ParcelTracker.TickReport<BlockPos, FluidShipment> report) {
        if (report.isQuiet()) {
            return;
        }

        for (ParcelTracker.Delivery<BlockPos, FluidShipment> delivery : report.delivered()) {
            FluidShipment shipment = delivery.payload();
            network.fluidLedger().recordDelivery(shipment.promiseId(), shipment.amountMb());
            FluidAccess.insert(level, delivery.destination(), shipment.resource(), shipment.amountMb());
        }

        for (ParcelTracker.Stranded<BlockPos, FluidShipment> stranded : report.stranded()) {
            FluidShipment shipment = stranded.payload();
            network.fluidLedger().cancel(shipment.promiseId());
        }
    }

    /**
     * Pulls up to {@code amountMb} of {@code fluid} from providers on the network toward
     * {@code dest}, nearest first, queued through {@link FluidSendQueue} so extraction
     * still happens at the shared pulse rate rather than all at once. Capacity-aware, same
     * reasoning as {@link EnergyRequestService#request}.
     *
     * @return how much was actually queued
     */
    public static int request(ServerLevel level, PipeNetwork network, BlockPos dest,
                              FluidResource fluid, int amountMb) {
        if (fluid.isEmpty() || amountMb <= 0) {
            return 0;
        }
        int alreadyInbound = network.fluidSendQueue().queuedTo(dest, fluid)
                + network.fluidLedger().inbound(dest, fluid);
        // Probe free space covering inbound + this ask. Probing only {@code amountMb} made
        // a second identical click see roomLeft=0 once the first batch was queued, even
        // when the tank still had plenty of empty capacity.
        int probeWanted = saturatingAdd(alreadyInbound, amountMb);
        int freeSpace = FluidAccess.insertable(level, dest, fluid, probeWanted);
        int roomLeft = Math.max(0, freeSpace - alreadyInbound);
        int remaining = Math.min(amountMb, roomLeft);
        if (remaining <= 0) {
            return 0;
        }

        // The requester's own tanks are not a source for the requester: a Provider pipe on
        // the far side of the same tank is a different node touching the same storage, so
        // skipping only the pipe position left fluid looping out and straight back in.
        Set<BlockPos> ownTanks = FluidAccess.attachedTanks(level, dest);

        int accepted = 0;
        for (BlockPos provider : providerNodesByDistance(network, dest)) {
            if (remaining <= 0) {
                break;
            }
            if (provider.equals(dest)
                    || !(level.getBlockState(provider).getBlock() instanceof FluidProviderPipeBlock)) {
                continue;
            }
            // extractable, not count: a tank that will not release what it holds would
            // otherwise queue jobs that die silently on the next pulse.
            // Minus what a Supplier maintaining this same tank is holding as its floor:
            // without it two tanks that each carry a Provider and a Supplier drain each
            // other forever and never settle. See SupplierReserves.
            int available = FluidAccess.extractable(level, provider, fluid, remaining, ownTanks)
                    - network.fluidSendQueue().queued(provider, fluid)
                    - SupplierReserves.reservedMb(level, provider, fluid);
            if (available <= 0) {
                continue;
            }
            int take = Math.min(remaining, available);
            network.fluidSendQueue().enqueue(provider, dest, fluid, take, ownTanks);
            remaining -= take;
            accepted += take;
        }
        return accepted;
    }

    /** {@code a + b} capped at {@link Integer#MAX_VALUE} so a huge inbound never overflows. */
    private static int saturatingAdd(int a, int b) {
        long sum = (long) a + (long) b;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    /**
     * Every fluid type available from providers reachable from {@code pos}, summed and
     * nearest-provider order broken ties aside, for the Request screen's grid.
     */
    public static List<Entry> catalog(ServerLevel level, PipeNetwork network, BlockPos pos) {
        Map<FluidResource, Integer> totals = new LinkedHashMap<>();
        for (BlockPos provider : providerNodesByDistance(network, pos)) {
            if (!(level.getBlockState(provider).getBlock() instanceof FluidProviderPipeBlock)) {
                continue;
            }
            for (Map.Entry<FluidResource, Integer> held : FluidAccess.summarize(level, provider).entrySet()) {
                int queued = network.fluidSendQueue().queued(provider, held.getKey());
                int reserved = SupplierReserves.reservedMb(level, provider, held.getKey());
                int free = held.getValue() - queued - reserved;
                if (free > 0) {
                    totals.merge(held.getKey(), free, Integer::sum);
                }
            }
        }
        List<Entry> catalog = new ArrayList<>(totals.size());
        for (Map.Entry<FluidResource, Integer> entry : totals.entrySet()) {
            catalog.add(new Entry(entry.getKey(), entry.getValue()));
        }
        return catalog;
    }

    /** One fluid catalog entry: the fluid and how much is free right now. */
    public record Entry(FluidResource fluid, int amountMb) {
    }

    /** Fluid provider nodes reachable from {@code dest}, nearest routing cost first. */
    private static List<BlockPos> providerNodesByDistance(PipeNetwork network, BlockPos dest) {
        RoutingSnapshot<BlockPos> routes = network.routes();
        return routes.routesFrom(dest)
                .map(table -> {
                    List<BlockPos> ordered = new ArrayList<>();
                    ordered.add(dest);
                    ordered.addAll(table.destinationsByCost());
                    return ordered;
                })
                .orElseGet(() -> routes.contains(dest) ? List.of(dest) : List.of());
    }
}
