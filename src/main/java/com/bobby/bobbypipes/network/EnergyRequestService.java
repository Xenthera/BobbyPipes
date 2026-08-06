package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.block.EnergyProviderPipeBlock;
import com.bobby.bobbypipes.transit.EnergyShipment;
import com.bobby.bobbypipes.transit.ParcelTier;
import com.bobby.bobbypipes.transit.ParcelTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Moves FE from one pipe to another as energy parcels.
 *
 * <p>Mirrors {@link RequestService}'s push/deliver/strand shape exactly, minus everything
 * that only makes sense for items: no crafting integration (energy is never made from
 * other energy), no drop-on-the-ground fallback (energy has no world representation to
 * drop). A packet that cannot be delivered, whether the destination fills up en route or
 * the route itself breaks with nowhere left to reseat to, is simply voided and its promise
 * cancelled or settled short, the same way a stranded parcel already gets voided rather
 * than invented a spill mechanic for something with no physical form.
 */
public final class EnergyRequestService {

    /** How long an energy promise may sit unfulfilled before it is released. */
    static final int PROMISE_TIMEOUT_TICKS = 20 * 60;

    /**
     * Most FE one energy parcel may carry.
     *
     * <p>This is a ceiling, not a packet size: a parcel carries whatever the source storage
     * actually released, which is what decides its {@link com.bobby.bobbypipes.transit.ParcelTier}.
     * A generator that dribbles out a few hundred FE per pull ships a few hundred FE.
     */
    public static final int MAX_PACKET_FE = ParcelTier.MAX_FE;

    private EnergyRequestService() {
    }

    /**
     * Pulls up to {@code amountFe} from energy providers on the network toward
     * {@code dest}, nearest first, queued through {@link EnergySendQueue} so extraction
     * still happens at the shared pulse rate rather than all at once.
     *
     * <p>Capacity-aware: checks how much {@code dest} could actually still receive (its
     * free space minus whatever is already queued or in flight toward it) before pulling
     * anything, so a full destination throttles how much gets drawn from providers in the
     * first place rather than shipping a full packet and voiding whatever does not fit.
     *
     * @return how much FE was actually queued, which may be less than requested if the
     *         network cannot currently supply that much or {@code dest} cannot currently
     *         receive that much
     */
    public static int request(ServerLevel level, PipeNetwork network, BlockPos dest, int amountFe) {
        if (amountFe <= 0) {
            return 0;
        }
        int alreadyInbound = network.energySendQueue().queuedTo(dest)
                + network.energyLedger().inbound(dest, EnergyKind.ENERGY);
        // Probe free space covering inbound + this ask. Probing only {@code amountFe} made
        // a second identical click see roomLeft=0 once the first batch was queued, even
        // when the buffer still had plenty of free capacity.
        int probeWanted = saturatingAdd(alreadyInbound, amountFe);
        int freeSpace = EnergyAccess.insertable(level, dest, probeWanted);
        int roomLeft = Math.max(0, freeSpace - alreadyInbound);
        int remaining = Math.min(amountFe, roomLeft);
        if (remaining <= 0) {
            return 0;
        }

        // The requester's own storages are not a source for the requester. Skipping only
        // the pipe position was not enough: a Provider pipe on the far side of the same
        // battery box is a different node touching the same box, so energy left it and
        // came straight back, on a loop, with the target never moving.
        Set<BlockPos> ownStorages = EnergyAccess.attachedStorages(level, dest);

        int accepted = 0;
        for (BlockPos provider : providerNodesByDistance(network, dest)) {
            if (remaining <= 0) {
                break;
            }
            if (provider.equals(dest)
                    || !(level.getBlockState(provider).getBlock() instanceof EnergyProviderPipeBlock)) {
                continue;
            }
            // extractable, not count: a generator that only pushes reports a full buffer it
            // will never hand over, and queueing against that just drops the job silently
            // on the next pulse.
            int available = EnergyAccess.extractable(level, provider, remaining, ownStorages)
 - network.energySendQueue().queued(provider)
 - SupplierReserves.reservedFe(level, provider);
            if (available <= 0) {
                continue;
            }
            int take = Math.min(remaining, available);
            network.energySendQueue().enqueue(provider, dest, take, ownStorages);
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
     * Roughly how much FE the network could currently supply toward {@code pos}, for the
     * Request screen's "network can give you about this much" display. Free stock only,
     * already-queued withdrawals are subtracted so this does not double count what is
     * about to leave anyway.
     */
    public static int availableFe(ServerLevel level, PipeNetwork network, BlockPos pos) {
        Set<BlockPos> ownStorages = EnergyAccess.attachedStorages(level, pos);
        int total = 0;
        for (BlockPos provider : providerNodesByDistance(network, pos)) {
            if (!(level.getBlockState(provider).getBlock() instanceof EnergyProviderPipeBlock)) {
                continue;
            }
            // Same exclusion as request: energy this pipe would only be shipping to itself
            // is not energy the network can give it.
            total += Math.max(0, EnergyAccess.extractable(level, provider, Integer.MAX_VALUE, ownStorages)
 - network.energySendQueue().queued(provider)
 - SupplierReserves.reservedFe(level, provider));
        }
        return total;
    }

    /** Energy provider nodes reachable from {@code dest}, nearest routing cost first. */
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

    /**
     * Sends {@code amountFe} from {@code from} toward {@code dest} as one energy parcel.
     *
     * @return how much was actually enqueued, either {@code amountFe} or 0 if {@code dest}
     *         is not reachable from {@code from} right now
     */
    public static int pushFromPipe(ServerLevel level,
                                   PipeNetwork network,
                                   BlockPos from,
                                   BlockPos dest,
                                   int amountFe) {
        if (amountFe <= 0 || from.equals(dest)) {
            return 0;
        }
        // One parcel means one parcel: anything past the ceiling is not sent rather than
        // silently split here, since the caller is told what actually went.
        amountFe = Math.min(amountFe, MAX_PACKET_FE);
        long promiseId = network.energyLedger().promise(from, dest, EnergyKind.ENERGY, amountFe,
                level.getGameTime() + PROMISE_TIMEOUT_TICKS);
        EnergyShipment shipment = new EnergyShipment(amountFe, promiseId);
        if (network.energyParcels().inject(shipment, from, dest, network.routes()).isPresent()) {
            return amountFe;
        }
        network.energyLedger().cancel(promiseId);
        return 0;
    }

    /**
     * Applies one tick's worth of arrivals and failures.
     *
     * <p>A delivered packet settles its promise and fills the destination's energy
     * storage. If the storage cannot take all of it (it filled up somewhere along the
     * way, since capacity was only checked when the packet was created) the remainder is
     * voided rather than held or dropped.
     */
    public static void handle(ServerLevel level,
                              PipeNetwork network,
                              ParcelTracker.TickReport<BlockPos, EnergyShipment> report) {
        if (report.isQuiet()) {
            return;
        }

        for (ParcelTracker.Delivery<BlockPos, EnergyShipment> delivery : report.delivered()) {
            EnergyShipment shipment = delivery.payload();
            PipeNetwork.settleEnergyDelivery(shipment.promiseId(), shipment.amountFe());
            EnergyAccess.insert(level, delivery.destination(), shipment.amountFe());
        }

        for (ParcelTracker.Stranded<BlockPos, EnergyShipment> stranded : report.stranded()) {
            EnergyShipment shipment = stranded.payload();
            PipeNetwork.cancelEnergyPromise(shipment.promiseId());
        }
    }
}
