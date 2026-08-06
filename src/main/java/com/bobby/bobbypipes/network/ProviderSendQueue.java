package com.bobby.bobbypipes.network;

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
 * <p>Uses the network's shared {@link ExtractPulseBudget}:
 * {@link PipeExtractRates#ITEMS_PER_PULSE} items every
 * {@link PipeExtractRates#PULSE_INTERVAL_TICKS} ticks, each pulse becoming its own parcel.
 * Large requests stay queued at the source until the provider has budget; stock is held via
 * {@link #queued} until extracted.
 */
public final class ProviderSendQueue {

    private final List<Job> jobs = new ArrayList<>();
    private final ExtractPulseBudget<BlockPos> budget;

    public ProviderSendQueue(ExtractPulseBudget<BlockPos> budget) {
        this.budget = budget;
    }

    /**
     * Queues {@code amount} to pull from {@code source} toward {@code dest}.
     *
     * <p>Nothing is extracted yet - that happens on {@link #tick} as the provider's send
     * budget allows.
     */
    public void enqueue(BlockPos source, BlockPos dest, ItemResource item, int amount) {
        enqueue(source, dest, item, amount, Set.of());
    }

    /**
     * As {@link #enqueue(BlockPos, BlockPos, ItemResource, int)}, but {@code excluded}
     * stores are off limits when the pull actually happens.
     *
     * <p>The planner already hid those stores when choosing sources; the set has to ride
     * along because extraction happens ticks later, and a provider touching both an
     * excluded chest and an allowed one would otherwise take from either.
     */
    public void enqueue(BlockPos source, BlockPos dest, ItemResource item, int amount,
                        Set<Object> excluded) {
        if (item.isEmpty() || amount <= 0) {
            return;
        }
        jobs.add(new Job(source.immutable(), dest.immutable(), null, item, amount, Set.copyOf(excluded)));
    }

    /**
     * Queues a pull whose destination may sit in another dimension (cross-dim link).
     */
    public void enqueueToward(BlockPos source, PipeNodeId dest, ItemResource item, int amount,
                              Set<Object> excluded) {
        if (item.isEmpty() || amount <= 0) {
            return;
        }
        jobs.add(new Job(source.immutable(), dest.pos().immutable(), dest, item, amount,
                Set.copyOf(excluded)));
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

    /** Total items still waiting to leave {@code source}, across every queued job. */
    public int queuedFrom(BlockPos source) {
        int total = 0;
        for (Job job : jobs) {
            if (job.source.equals(source)) {
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

    /**
     * How many of {@code item} are scheduled to arrive at {@code dest}, matching full
     * {@link PipeNodeId} when the job was enqueued cross-dimensionally.
     */
    public int queuedToward(PipeNodeId dest, ItemResource item) {
        if (item.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Job job : jobs) {
            if (!job.item.equals(item)) {
                continue;
            }
            if (job.destNode != null) {
                if (job.destNode.equals(dest)) {
                    total += job.remaining;
                }
            } else if (job.dest.equals(dest.pos())) {
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
     * Cancels queued pulls toward {@code dest}, matching full {@link PipeNodeId} when the
     * job was enqueued cross-dimensionally (and pos-only for legacy same-dim jobs).
     */
    public int cancelToward(PipeNodeId dest) {
        int cancelled = 0;
        Iterator<Job> iterator = jobs.iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            if (matchesDest(job, dest)) {
                cancelled += job.remaining;
                iterator.remove();
            }
        }
        return cancelled;
    }

    /**
     * Cancels queued pulls of {@code item} toward {@code dest}.
     */
    public int cancelToward(PipeNodeId dest, ItemResource item) {
        if (item.isEmpty()) {
            return 0;
        }
        int cancelled = 0;
        Iterator<Job> iterator = jobs.iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            if (job.item.equals(item) && matchesDest(job, dest)) {
                cancelled += job.remaining;
                iterator.remove();
            }
        }
        return cancelled;
    }

    private static boolean matchesDest(Job job, PipeNodeId dest) {
        if (job.destNode != null) {
            return job.destNode.equals(dest);
        }
        return job.dest.equals(dest.pos());
    }

    /**
     * Extracts and injects as many pulse-sized parcels as current provider budgets allow.
     *
     * @return how many items left providers this tick
     */
    public int tick(ServerLevel level,
                    PipeNetwork network,
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
            Direction from =
                    ProviderAccess.sideHolding(level, job.source, job.item, job.excluded).orElse(null);
            int taken = ProviderAccess.extract(level, job.source, job.item, want, job.excluded);
            if (taken <= 0) {
                // Chest emptied or pipe broken - drop the rest of this job.
                iterator.remove();
                continue;
            }

            long promiseId = ledger.promise(
                    job.source, job.dest, job.item, taken,
                    gameTime + RequestService.PROMISE_TIMEOUT_TICKS);
            ItemShipment shipment = new ItemShipment(job.item, taken, promiseId, from);
            PipeNodeId destNode = job.destNode != null
                    ? job.destNode
                    : PipeNodeId.of(level, job.dest);
            boolean injected = network.injectItemToward(shipment, job.source, destNode).isPresent();
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
        private final PipeNodeId destNode;
        private final ItemResource item;
        /** Store identities this job must not pull from, normally the requester's own. */
        private final Set<Object> excluded;
        private int remaining;

        private Job(BlockPos source, BlockPos dest, PipeNodeId destNode, ItemResource item,
                    int remaining, Set<Object> excluded) {
            this.source = source;
            this.dest = dest;
            this.destNode = destNode;
            this.item = item;
            this.remaining = remaining;
            this.excluded = excluded;
        }
    }
}

