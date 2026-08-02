package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.request.DeliveryLedger;
import com.bobby.bobbypipes.request.RequestPlan;
import com.bobby.bobbypipes.transit.ItemShipment;
import com.bobby.bobbypipes.transit.ParcelTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Turns a planned request into items actually moving.
 *
 * <p>Committing is the point where a plan stops being hypothetical. Each withdrawal is
 * really extracted, recorded as a promise, and handed to the parcel tracker. Every step
 * can come up short and each shortfall is handled rather than assumed away, because the
 * world can change between planning and committing.
 */
public final class RequestService {

    /** Ticks a promise may go unfulfilled before it is released. */
    private static final int PROMISE_TIMEOUT_TICKS = 20 * 60;

    private RequestService() {
    }

    /**
     * What committing achieved.
     *
     * @param shipped   how many items are now in flight
     * @param requested how many the plan intended to move
     */
    public record Commitment(int shipped, int requested) {

        public boolean isComplete() {
            return shipped >= requested;
        }

        public int shortfall() {
            return Math.max(0, requested - shipped);
        }
    }

    /**
     * Extracts the plan's withdrawals and sends them to {@code requester}.
     *
     * <p>Crafting steps are ignored for now; no crafter pipe exists to run them.
     */
    public static Commitment commit(ServerLevel level,
                                    PipeNetwork network,
                                    RequestPlan<BlockPos, ItemResource> plan,
                                    BlockPos requester) {
        DeliveryLedger<BlockPos, ItemResource> ledger = network.ledger();
        ParcelTracker<BlockPos, ItemShipment> parcels = network.parcels();
        RoutingSnapshot<BlockPos> routes = network.routes();

        int requested = 0;
        int shipped = 0;

        for (RequestPlan.Withdrawal<BlockPos, ItemResource> withdrawal : plan.withdrawals()) {
            requested += withdrawal.amount();

            // The plan was built from a snapshot of the world. Between then and now a
            // hopper may have emptied the chest, so take what is actually there.
            int taken = InventoryAccess.extract(
                    level, withdrawal.source(), withdrawal.item(), withdrawal.amount());
            if (taken <= 0) {
                continue;
            }

            long promiseId = ledger.promise(withdrawal.source(), requester, withdrawal.item(),
                    taken, level.getGameTime() + PROMISE_TIMEOUT_TICKS);

            ItemShipment shipment = new ItemShipment(withdrawal.item(), taken, promiseId);
            boolean injected = parcels
                    .inject(shipment, withdrawal.source(), requester, routes)
                    .isPresent();

            if (injected) {
                shipped += taken;
            } else {
                // Nowhere to send them after all. Undo cleanly rather than leaving the
                // items deleted and the promise dangling.
                ledger.cancel(promiseId);
                InventoryAccess.insertOrDrop(level, withdrawal.source(), withdrawal.item(), taken);
            }
        }

        return new Commitment(shipped, requested);
    }

    /**
     * Applies one tick's worth of arrivals and failures.
     *
     * <p>Deliveries settle their promise and go into an inventory at the destination.
     * Stranded parcels are put down where they gave up. Either way the items exist
     * somewhere a player can reach them.
     */
    public static void handle(ServerLevel level,
                              PipeNetwork network,
                              ParcelTracker.TickReport<BlockPos, ItemShipment> report) {
        if (report.isQuiet()) {
            return;
        }

        for (ParcelTracker.Delivery<BlockPos, ItemShipment> delivery : report.delivered()) {
            ItemShipment shipment = delivery.payload();
            network.ledger().recordDelivery(shipment.promiseId(), shipment.count());
            InventoryAccess.insertOrDrop(
                    level, delivery.destination(), shipment.resource(), shipment.count());
        }

        for (ParcelTracker.Stranded<BlockPos, ItemShipment> stranded : report.stranded()) {
            ItemShipment shipment = stranded.payload();
            network.ledger().cancel(shipment.promiseId());
            InventoryAccess.insertOrDrop(
                    level, stranded.at(), shipment.resource(), shipment.count());
        }
    }
}
