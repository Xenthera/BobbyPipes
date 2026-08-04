package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.block.ProviderPipeBlock;
import com.bobby.bobbypipes.request.DeliveryLedger;
import com.bobby.bobbypipes.transit.ItemShipment;
import com.bobby.bobbypipes.transit.ParcelTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;
import java.util.List;

/**
 * Drains accepted withdrawals through providers at their send rate.
 *
 * <p>Basic providers send {@link ProviderPipeBlock#ITEMS_PER_PULSE} items every
 * {@link ProviderPipeBlock#PULSE_INTERVAL_TICKS} ticks, each pulse becoming its own parcel.
 * Large requests stay queued at the source until the provider has budget; stock is held via
 * {@link #queued} until extracted.
 */
public final class ProviderSendQueue {

    private final List<Job> jobs = new ArrayList<>();
    private final ProviderPulseBudget<BlockPos> budget = new ProviderPulseBudget<>(
            ProviderPipeBlock.ITEMS_PER_PULSE, ProviderPipeBlock.PULSE_INTERVAL_TICKS);

    /**
     * Queues {@code amount} to pull from {@code source} toward {@code dest}.
     *
     * <p>Nothing is extracted yet  -  that happens on {@link #tick} as the provider's send
     * budget allows.
     */
    public void enqueue(BlockPos source, BlockPos dest, ItemResource item, int amount) {
        if (item.isEmpty() || amount <= 0) {
            return;
        }
        jobs.add(new Job(source.immutable(), dest.immutable(), item, amount));
    }

    /** How many of {@code item} are still waiting to leave {@code source}. */
    public int queued(BlockPos source, ItemResource item) {
        int total = 0;
        for (Job job : jobs) {
            if (job.source.equals(source) && job.item.equals(item)) {
                total += job.remaining;
            }
        }
        return total;
    }

    /** How many of {@code item} are already scheduled to arrive at {@code dest}. */
    public int queuedTo(BlockPos dest, ItemResource item) {
        int total = 0;
        for (Job job : jobs) {
            if (job.dest.equals(dest) && job.item.equals(item)) {
                total += job.remaining;
            }
        }
        return total;
    }

    public int jobCount() {
        return jobs.size();
    }

    /**
     * Cancels every queued pull whose destination is {@code dest}.
     *
     * @return how many items were cancelled (sum of remaining amounts)
     */
    public int cancelTo(BlockPos dest) {
        int cancelled = 0;
        Iterator<Job> iterator = jobs.iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            if (job.dest.equals(dest)) {
                cancelled += job.remaining;
                iterator.remove();
            }
        }
        return cancelled;
    }

    /**
     * Cancels queued pulls of {@code item} toward {@code dest}.
     *
     * @return how many items were cancelled
     */
    public int cancelTo(BlockPos dest, ItemResource item) {
        if (item.isEmpty()) {
            return 0;
        }
        int cancelled = 0;
        Iterator<Job> iterator = jobs.iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            if (job.dest.equals(dest) && job.item.equals(item)) {
                cancelled += job.remaining;
                iterator.remove();
            }
        }
        return cancelled;
    }

    /**
     * Extracts and injects as many pulse-sized parcels as current provider budgets allow.
     *
     * @return how many items left providers this tick
     */
    public int tick(ServerLevel level,
                    DeliveryLedger<BlockPos, ItemResource> ledger,
                    ParcelTracker<BlockPos, ItemShipment> parcels,
                    RoutingSnapshot<BlockPos> routes) {
        if (jobs.isEmpty()) {
            return 0;
        }

        long gameTime = level.getGameTime();
        int shipped = 0;
        // One dispatch per provider per tick. Draining several in the same tick put two
        // different items on the network at the same point at the same instant, so a
        // request needing shells and logs left as a single overlapping blob. Sending one
        // item type per tick makes each leave as its own parcel.
        Set<BlockPos> dispatchedThisTick = new HashSet<>();
        Iterator<Job> iterator = jobs.iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            if (!dispatchedThisTick.add(job.source)) {
                continue;
            }
            int allow = budget.budget(job.source, gameTime);
            if (allow <= 0) {
                continue;
            }

            int want = Math.min(job.remaining, allow);
            // Resolved before the extract, while the container still holds the item, so
            // the parcel sets off from the arm it actually came out of.
            Direction from = InventoryAccess.sideHolding(level, job.source, job.item).orElse(null);
            int taken = InventoryAccess.extract(level, job.source, job.item, want);
            if (taken <= 0) {
                // Chest emptied or pipe broken  -  drop the rest of this job.
                iterator.remove();
                continue;
            }

            long promiseId = ledger.promise(
                    job.source, job.dest, job.item, taken,
                    gameTime + RequestService.PROMISE_TIMEOUT_TICKS);
            ItemShipment shipment = new ItemShipment(job.item, taken, promiseId, from);
            boolean injected = parcels.inject(shipment, job.source, job.dest, routes).isPresent();
            if (!injected) {
                ledger.cancel(promiseId);
                InventoryAccess.insertOrDrop(level, job.source, job.item, taken);
                iterator.remove();
                continue;
            }

            budget.consume(job.source, taken);
            job.remaining -= taken;
            shipped += taken;
            if (job.remaining <= 0) {
                iterator.remove();
            }
        }
        return shipped;
    }

    private static final class Job {
        private final BlockPos source;
        private final BlockPos dest;
        private final ItemResource item;
        private int remaining;

        private Job(BlockPos source, BlockPos dest, ItemResource item, int remaining) {
            this.source = source;
            this.dest = dest;
            this.item = item;
            this.remaining = remaining;
        }
    }
}

