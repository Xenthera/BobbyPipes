package com.bobby.bobbypipes.logistics;

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
        Set<Object> fixed = Set.copyOf(excluded);
        jobs.add(new Job(source.immutable(), dest.immutable(), null, item, amount, () -> fixed));
    }

    /**
     * Queues a pull whose destination may sit in another dimension (cross-dim link).
     */
    public void enqueueToward(BlockPos source, PipeNodeId dest, ItemResource item, int amount,
                              Set<Object> excluded) {
        if (item.isEmpty() || amount <= 0) {
            return;
        }
        Set<Object> fixed = Set.copyOf(excluded);
        jobs.add(new Job(source.immutable(), dest.pos().immutable(), dest, item, amount,
                () -> fixed));
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
        // Served jobs move to the back once the sweep is done; mutating mid-iteration would
        // let the same job be picked up again in this tick.
        List<Job> rotated = new ArrayList<>();
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
            // Ask the destination what it can take before pulling anything out of the chest.
            // Without this a provider shipped on the extract budget alone, the parcel arrived
            // at a machine whose input slot was already full, and the overflow was dropped on
            // the floor while the promise settled as delivered - so the craft job believed it
            // had been supplied, its binding was spent, and it waited forever. Items that do
            // not fit are not cancelled; they are simply not sent yet.
            int room = roomAtDestination(level, job, want);
            if (room == 0) {
                continue;
            }
            if (room > 0) {
                want = Math.min(want, room);
            }
            // Resolved before the extract, while the container still holds the item, so
            // the parcel sets off from the arm it actually came out of.
            Direction from =
                    ProviderAccess.sideHolding(level, job.source, job.item, job.excluded.get())
                            .orElse(null);
            if (!network.power().canAfford(job.source,
                    com.bobby.bobbypipes.logistics.power.LogisticsPowerCosts.PROVIDER)) {
                continue;
            }
            int taken = ProviderAccess.extract(level, job.source, job.item, want, job.excluded.get());
            if (taken <= 0) {
                // Not necessarily "the chest is empty for good". Under load several jobs
                // compete for one provider and a read can come back empty for a tick while
                // stock is still arriving. Dropping the job here spent a craft job's stock
                // binding on a delivery that never happened, and since bound pulls never
                // re-query live supply the craft then waited on raw material that was sitting
                // in the chest. Keep the job and try again.
                continue;
            }
            if (!network.power().trySpend(job.source,
                    com.bobby.bobbypipes.logistics.power.PowerSpendKind.PROVIDER)) {
                InventoryAccess.insertOrDrop(level, job.source, job.item, taken);
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
                // No route this tick - during a rebuild, or while a link's far side is
                // loading. The items go straight back where they came from, so the job is
                // still exactly as valid as it was a moment ago: keep it and try again.
                //
                // Removing it here is what silently shrank a large order. The craft job that
                // asked for these had already spent its stock binding, and bound pulls never
                // re-query supply, so the shortfall was permanent and the crafter sat waiting
                // on material the provider still had.
                ledger.cancel(promiseId);
                InventoryAccess.insertOrDrop(level, job.source, job.item, taken);
                continue;
            }

            budget.consume(job.source, taken);
            job.remaining -= taken;
            shipped += taken;
            if (job.remaining <= 0) {
                iterator.remove();
            } else {
                // Round robin across destinations, the same rule the craft side follows and
                // the one Logistics Pipes applies in its order manager. Only one dispatch
                // leaves a provider per tick, so serving strictly head-first drains the first
                // destination's whole order before the next sees anything: one chest feeding
                // three copper crafters fed one of them and starved the other two.
                rotated.add(job);
                iterator.remove();
            }
        }
        jobs.addAll(rotated);
        return shipped;
    }

    /**
     * Queued withdrawals leaving a source in {@code component}, for the monitor's order queue.
     *
     * <p>Filtered by source rather than destination: the question this view answers is "what
     * is this provider still going to send, and to whom".
     */
    public java.util.List<com.bobby.bobbypipes.network.payload.CraftMonitorPayload.Order>
            orderRows(ServerLevel level, java.util.Set<BlockPos> component) {
        java.util.List<com.bobby.bobbypipes.network.payload.CraftMonitorPayload.Order> rows =
                new ArrayList<>();
        for (Job job : jobs) {
            if (job.remaining <= 0 || !component.contains(job.source)) {
                continue;
            }
            net.minecraft.world.item.ItemStack stack = job.item.toStack(1);
            if (!stack.isEmpty()) {
                rows.add(new com.bobby.bobbypipes.network.payload.CraftMonitorPayload.Order(
                        stack, job.source, job.dest, job.remaining, false,
                        InventoryAccess.attachedBlockIcon(level, job.source)));
            }
        }
        return rows;
    }

    /**
     * A queued withdrawal as save data.
     *
     * <p>Deliberately without the job's excluded store identities. {@code StorageIdentities.of}
     * can return a key wrapping a live object reference (a BobbyChests shared item list), which
     * means nothing after a reload. They are always the destination's own stores, so they are
     * recomputed from {@code dest} on restore instead.
     */
    public record SavedJob(BlockPos source, BlockPos dest, PipeNodeId destNode,
                           ItemResource item, int remaining) {
    }

    public java.util.List<SavedJob> capture() {
        java.util.List<SavedJob> out = new ArrayList<>(jobs.size());
        for (Job job : jobs) {
            out.add(new SavedJob(job.source, job.dest, job.destNode, job.item, job.remaining));
        }
        return out;
    }

    /**
     * @param excludedFor rebuilds the store identities a job must not pull from, given its
     *                    destination - see {@link SavedJob}
     */
    public void restore(java.util.List<SavedJob> loaded,
                        java.util.function.Function<BlockPos, Set<Object>> excludedFor) {
        jobs.clear();
        for (SavedJob saved : loaded) {
            if (saved.remaining() <= 0 || saved.item().isEmpty()) {
                continue;
            }
            BlockPos dest = saved.dest();
            jobs.add(new Job(saved.source(), dest, saved.destNode(), saved.item(),
                    saved.remaining(), () -> excludedFor.apply(dest)));
        }
    }

    /**
     * How much of this job's item its destination can accept right now.
     *
     * <p>Counts only parcels physically travelling, never the queue. A queued job has taken
     * nothing out of a chest yet and so occupies no space at the far end; counting the queue
     * would include this job's own outstanding amount and stall it against itself.
     *
     * <p>The probe asks for the ask plus what is flying and subtracts the flying afterwards -
     * a capacity probe returns at most what it is asked for, so probing the bare ask reports
     * no room as soon as anything is on its way.
     *
     * @return free space, or -1 when the destination has no inventory to measure, in which
     *         case the caller ships as before rather than waiting forever
     */
    private static int roomAtDestination(ServerLevel level, Job job, int want) {
        PipeNodeId destNode = job.destNode != null
                ? job.destNode
                : PipeNodeId.of(level, job.dest);
        ServerLevel destLevel = LinkPipeRegistry.levelOf(level.getServer(), destNode);
        if (destLevel == null || !destLevel.hasChunkAt(destNode.pos())) {
            return -1;
        }
        if (InventoryAccess.inventorySide(destLevel, destNode.pos()).isEmpty()) {
            // Nothing attached to measure - a request pipe with no container, say. Gating on
            // a capacity of zero would mean it never received anything at all.
            return -1;
        }
        int flying = 0;
        for (PipeNetwork net : PipeNetwork.instances()) {
            flying += net.flyingItemsToward(destNode, job.item);
        }
        int probe = (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, want) + Math.max(0, flying));
        int space = InventoryAccess.insertable(destLevel, destNode.pos(), job.item, probe);
        return Math.max(0, space - flying);
    }

    /** Resolves once and keeps the answer, so an exclusion set is not rebuilt every tick. */
    private static java.util.function.Supplier<Set<Object>> memoize(
            java.util.function.Supplier<Set<Object>> source) {
        return new java.util.function.Supplier<>() {
            private Set<Object> value;

            @Override
            public Set<Object> get() {
                if (value == null) {
                    value = source.get();
                }
                return value;
            }
        };
    }

    private static final class Job {
        private final BlockPos source;
        private final BlockPos dest;
        private final PipeNodeId destNode;
        private final ItemResource item;
        /**
         * Store identities this job must not pull from, normally the requester's own.
         *
         * <p>A supplier rather than a set because a restored job is built while the world is
         * still loading, when reading the destination's block entities would both force its
         * chunk in and, if that chunk is absent, hand back an empty exclusion - which would
         * let the requester pull from its own chest in a loop. Resolved once, on first use.
         */
        private final java.util.function.Supplier<Set<Object>> excluded;
        private int remaining;

        private Job(BlockPos source, BlockPos dest, PipeNodeId destNode, ItemResource item,
                    int remaining, java.util.function.Supplier<Set<Object>> excluded) {
            this.source = source;
            this.dest = dest;
            this.destNode = destNode;
            this.item = item;
            this.remaining = remaining;
            this.excluded = memoize(excluded);
        }
    }
}

