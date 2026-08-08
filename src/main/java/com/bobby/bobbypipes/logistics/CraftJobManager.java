package com.bobby.bobbypipes.logistics;

import com.bobby.bobbypipes.block.entity.CraftingPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.PatternTableBlockEntity;
import com.bobby.bobbypipes.craft.CraftPattern;
import com.bobby.bobbypipes.logistics.craft.CraftJobPolicy;
import com.bobby.bobbypipes.logistics.craft.CraftParticles;
import com.bobby.bobbypipes.network.payload.CraftMonitorPayload;
import com.bobby.bobbypipes.network.payload.CraftStatusPayload;
import com.bobby.bobbypipes.request.RequestPlan;
import com.bobby.bobbypipes.transit.ItemShipment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Queues and advances autocraft jobs (LP deliver-and-wait style).
 *
 * <p>Policy (pull need, phase/arm, remaining inputs, surplus) lives in
 * {@link CraftJobPolicy}. This class is the world adapter: inventories, send queue,
 * parcels, satellites.
 *
 * <p>Only one job per crafter is active at a time (oldest first). Fail/timeout cancels
 * inbound queue work and reclaim in-flight parcels headed to the job's buffers.
 */
public final class CraftJobManager {

    private static ServerLevel levelOf(ServerLevel any, PipeNodeId node) {
        return LinkPipeRegistry.levelOf(any.getServer(), node);
    }

    private static PipeNetwork networkOf(ServerLevel any, PipeNodeId node) {
        ServerLevel nodeLevel = levelOf(any, node);
        return nodeLevel == null ? null : PipeNetwork.get(nodeLevel);
    }

    private final List<Job> jobs = new ArrayList<>();
    /**
     * Job ids are unique across every manager, not just this one.
     *
     * <p>The Autocraft Monitor lists jobs from across live interdimensional links, so the
     * cancel button has only an id to go on and has to find the owning network by sweeping.
     * A per-manager counter handed out id 1 in every dimension, which would have cancelled
     * whichever network happened to be swept first. Ids are never persisted, so a plain
     * static counter is enough.
     */
    private static final java.util.concurrent.atomic.AtomicLong NEXT_JOB_ID =
            new java.util.concurrent.atomic.AtomicLong(1L);
    private long nextRequestId = 1L;

    /** How long a failure stays visible in the overlay after the job is gone. */
    private static final int FAILURE_MEMORY_TICKS = 20 * 45;

    /**
     * Why recently removed jobs died.
     *
     * <p>A failed job used to vanish leaving one particle burst, so the usual symptom was
     * an upstream crafter producing into nowhere with no explanation anywhere. Keeping the
     * reason around is the difference between diagnosing that and guessing at it.
     */
    private final List<Failure> failures = new ArrayList<>();

    private void recordFailure(PipeNodeId crafter, ItemResource output, String reason, long gameTime) {
        failures.removeIf(f -> f.crafter.equals(crafter.pos())
                && f.dimension.equals(crafter.dimension()));
        failures.add(new Failure(
                crafter.dimension(), crafter.pos().immutable(), output, reason,
                gameTime + FAILURE_MEMORY_TICKS));
    }

    private static final class Failure {
        private final ResourceKey<Level> dimension;
        private final BlockPos crafter;
        private final ItemResource output;
        private final String reason;
        private final long expires;

        private Failure(ResourceKey<Level> dimension,
                        BlockPos crafter,
                        ItemResource output,
                        String reason,
                        long expires) {
            this.dimension = dimension;
            this.crafter = crafter;
            this.output = output;
            this.reason = reason;
            this.expires = expires;
        }
    }

    public int jobCount() {
        return jobs.size();
    }

    /**
     * Runs {@code crafter} is already committed to across every open job.
     *
     * <p>Planning uses this to hand new work to the least busy crafter, rather than splitting
     * evenly and piling onto one that is already backed up.
     */
    public int outstandingRunsAt(PipeNodeId crafter) {
        int runs = 0;
        for (Job job : jobs) {
            if (job.crafter.equals(crafter)) {
                runs += Math.max(0, job.runsRemaining);
            }
        }
        return runs;
    }

    // ---------------------------------------------------------------- save data

    /** One plan-bound input as save data. {@code fromCraft} distinguishes the two origins. */
    public record SavedInput(ItemResource item, int amount, boolean fromCraft, PipeNodeId from) {
    }

    /** A downstream crafter's outstanding claim on this job's output. */
    public record SavedOwed(PipeNodeId consumer, ItemResource item, int remaining) {
    }

    /** Stock a bound provider still owes this job. */
    public record SavedStock(PipeNodeId provider, ItemResource item, int remaining) {
    }

    /** A stack of output extracted but not yet placed, or mid-extract. */
    public record SavedStack(ItemResource item, int count) {
    }

    /**
     * Where a job is in its run cycle.
     *
     * <p>Grouped rather than three loose booleans on {@link SavedJob} because the record codec
     * builder tops out at sixteen fields, and these three belong together anyway.
     */
    public record SavedProgress(boolean waitingForOutput, boolean armedThisRun,
                                boolean requestedThisRun) {
    }

    /** One craft job, flattened for save data. */
    public record SavedJob(long id, long requestId, PipeNodeId crafter, PipeNodeId requester,
                           ItemResource output, int runsRemaining, int remainingForRequester,
                           List<SavedInput> inputs, List<PipeNodeId> dependsOn,
                           List<SavedStock> stockRemaining, List<SavedOwed> owes,
                           SavedProgress progress, List<SavedStack> outputLeft,
                           int outputLeftRuns, List<SavedStack> held) {
    }

    /** Every live job plus the request counter, for save data. */
    public record Saved(List<SavedJob> jobs, long nextRequestId) {
    }

    public Saved capture() {
        List<SavedJob> out = new ArrayList<>(jobs.size());
        for (Job job : jobs) {
            List<SavedInput> inputs = new ArrayList<>();
            for (RequestPlan.Sourced<PipeNodeId, ItemResource> input : job.inputs) {
                PipeNodeId from = input.origin() instanceof RequestPlan.Origin.Stock<PipeNodeId> stock
                        ? stock.provider()
                        : ((RequestPlan.Origin.Craft<PipeNodeId>) input.origin()).crafter();
                inputs.add(new SavedInput(input.item(), input.amount(), input.fromCraft(), from));
            }
            List<SavedStock> stock = new ArrayList<>();
            for (Map.Entry<StockBinding, Integer> entry : job.stockRemaining.entrySet()) {
                stock.add(new SavedStock(entry.getKey().provider(), entry.getKey().item(),
                        entry.getValue()));
            }
            List<SavedOwed> owed = new ArrayList<>();
            for (Owed owe : job.owes) {
                owed.add(new SavedOwed(owe.consumer, owe.item, owe.remaining));
            }
            out.add(new SavedJob(job.id, job.requestId, job.crafter, job.requester, job.output,
                    job.runsRemaining, job.remainingForRequester, inputs,
                    List.copyOf(job.dependsOn), stock, owed,
                    new SavedProgress(job.phase == CraftJobPolicy.Phase.WAIT_OUTPUT,
                            job.armedThisRun, job.requestedThisRun),
                    toStacks(job.outputLeft), job.outputLeftRuns, toStacks(job.held)));
        }
        return new Saved(out, nextRequestId);
    }

    /**
     * Replaces the queue with {@code saved}.
     *
     * <p>Seeds the shared job-id counter past every restored id. The counter is static because
     * the monitor cancels by id across networks, so a collision after a reload would cancel a
     * job in another dimension.
     */
    public void restore(Saved saved) {
        jobs.clear();
        long highest = 0L;
        for (SavedJob data : saved.jobs()) {
            List<RequestPlan.Sourced<PipeNodeId, ItemResource>> inputs = new ArrayList<>();
            for (SavedInput input : data.inputs()) {
                RequestPlan.Origin<PipeNodeId> origin = input.fromCraft()
                        ? new RequestPlan.Origin.Craft<>(input.from())
                        : new RequestPlan.Origin.Stock<>(input.from());
                inputs.add(new RequestPlan.Sourced<>(input.item(), input.amount(), origin));
            }
            Job job = new Job(data.id(), data.requestId(), data.crafter(), data.requester(),
                    data.output(), data.runsRemaining(), data.remainingForRequester(),
                    inputs, data.dependsOn());
            // The constructor derives stockRemaining from inputs; the saved figures are what
            // is actually left after earlier pulls, so they replace it wholesale.
            job.stockRemaining.clear();
            for (SavedStock stock : data.stockRemaining()) {
                job.stockRemaining.put(
                        new StockBinding(stock.provider(), stock.item()), stock.remaining());
            }
            for (SavedOwed owed : data.owes()) {
                job.owes.add(new Owed(owed.consumer(), owed.item(), owed.remaining()));
            }
            job.phase = data.progress().waitingForOutput()
                    ? CraftJobPolicy.Phase.WAIT_OUTPUT
                    : CraftJobPolicy.Phase.GATHER;
            job.armedThisRun = data.progress().armedThisRun();
            job.requestedThisRun = data.progress().requestedThisRun();
            job.outputLeftRuns = data.outputLeftRuns();
            if (!data.outputLeft().isEmpty()) {
                job.outputLeft = new LinkedHashMap<>();
                for (SavedStack stack : data.outputLeft()) {
                    job.outputLeft.merge(stack.item(), stack.count(), Integer::sum);
                }
            }
            for (SavedStack stack : data.held()) {
                job.held.merge(stack.item(), stack.count(), Integer::sum);
            }
            jobs.add(job);
            highest = Math.max(highest, job.id);
        }
        nextRequestId = Math.max(nextRequestId, saved.nextRequestId());
        long firstFreeId = highest + 1L;
        NEXT_JOB_ID.updateAndGet(current -> Math.max(current, firstFreeId));
    }

    private static List<SavedStack> toStacks(Map<ItemResource, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return List.of();
        }
        List<SavedStack> out = new ArrayList<>(counts.size());
        for (Map.Entry<ItemResource, Integer> entry : counts.entrySet()) {
            out.add(new SavedStack(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    /**
     * Enqueues craft steps from {@code plan}.
     *
     * @param requestedItem   original demand item (used to decide requester deliveries)
     * @param requestedAmount how many of that item the request still needs after stock pulls
     * @return how many of {@code requestedItem} these jobs intend to deliver to the requester
     *         (accepted to craft - not yet delivered)
     */
    public int enqueue(RequestPlan<PipeNodeId, ItemResource> plan,
                       PipeNodeId requester,
                       ItemResource requestedItem,
                       int requestedAmount,
                       long gameTime) {
        int stillNeed = Math.max(0, requestedAmount);
        int acceptedForRequester = 0;
        // One id for every step of this plan so a later replan can drop the whole tree
        // together (or leave it alone once any step has started).
        long requestId = nextRequestId++;
        // Steps arrive leaf first, so a step's upstream producers already have jobs by the
        // time we wire its inputs.
        Map<PipeNodeId, Job> producerFor = new java.util.HashMap<>();
        for (RequestPlan.CraftStep<PipeNodeId, ItemResource> step : plan.crafts()) {
            if (step.runs() <= 0) {
                continue;
            }
            int forRequester = 0;
            if (step.output().equals(requestedItem) && stillNeed > 0) {
                forRequester = Math.min(stillNeed, step.totalOutput());
                stillNeed -= forRequester;
                acceptedForRequester += forRequester;
            }
            // Tell each upstream producer precisely what this step is owed. Without this
            // the output relied on an opportunistic scan and otherwise fell through to
            // "surplus", which meant intermediates ended up on the floor.
            for (RequestPlan.Sourced<PipeNodeId, ItemResource> input : step.inputs()) {
                if (input.origin() instanceof RequestPlan.Origin.Craft<PipeNodeId> from) {
                    Job producer = producerFor.get(from.crafter());
                    if (producer != null) {
                        producer.owes.add(new Owed(step.crafter(), input.item(), input.amount()));
                    }
                }
            }

            // One crafter has one pattern, but the planner may still hand back two steps
            // for it (e.g. a shared intermediate crafted once for a sibling's need and
            // again for this step's own shortfall once that surplus ran out). Treating
            // those as two Jobs orphans the first from producerFor as soon as the second
            // is recorded, so anything resolved after that point attaches its owed claim
            // to a job that can never produce enough to cover it. Folding the step into
            // the existing job keeps one physical job per crafter per request, so owed
            // totals always match what it will actually produce.
            Job existing = producerFor.get(step.crafter());
            if (existing != null) {
                existing.mergeStep(step, forRequester);
            } else {
                Job job = new Job(
                        NEXT_JOB_ID.getAndIncrement(),
                        requestId,
                        step.crafter(),
                        requester,
                        step.output(),
                        step.runs(),
                        forRequester,
                        step.inputs(),
                        step.dependsOn());
                producerFor.put(step.crafter(), job);
                jobs.add(job);
            }
        }
        return acceptedForRequester;
    }

    /**
     * Drops whole craft request groups that still owe {@code item} to {@code requester}
     * but have not started yet.
     *
     * <p>Used by Supplier restock when providers later gain stock: cancel the queued craft
     * tree and pull from chests instead. A group is left alone once any of its jobs has
     * pulled bound stock, armed a run, or left {@code GATHER} - that work is already in
     * progress and must not be yanked out from under a machine. Merely being listed (or
     * waiting on a still-queued upstream) does not count as started.
     *
     * <p>Unstarted jobs have never touched the send queue or buffers, so they are removed
     * silently (no reclaim / failure card).
     *
     * @return how many of {@code item} were released from requester claims
     */
    public int cancelUnstartedOwedTo(BlockPos requester, ItemResource item) {
        if (item.isEmpty() || jobs.isEmpty()) {
            return 0;
        }
        Set<Long> candidateGroups = new LinkedHashSet<>();
        for (Job job : jobs) {
            if (job.requester.pos().equals(requester)
                    && job.output.equals(item)
                    && job.remainingForRequester > 0) {
                candidateGroups.add(job.requestId);
            }
        }
        if (candidateGroups.isEmpty()) {
            return 0;
        }
        Set<Long> cancellable = new LinkedHashSet<>();
        for (long requestId : candidateGroups) {
            if (isUnstartedGroup(requestId)) {
                cancellable.add(requestId);
            }
        }
        if (cancellable.isEmpty()) {
            return 0;
        }
        int released = 0;
        Iterator<Job> iterator = jobs.iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            if (!cancellable.contains(job.requestId)) {
                continue;
            }
            if (job.requester.pos().equals(requester) && job.output.equals(item)) {
                released += Math.max(0, job.remainingForRequester);
            }
            iterator.remove();
        }
        return released;
    }

    /** True when every job in the request group has yet to touch the network or machine. */
    private boolean isUnstartedGroup(long requestId) {
        boolean any = false;
        for (Job job : jobs) {
            if (job.requestId != requestId) {
                continue;
            }
            any = true;
            if (hasStarted(job)) {
                return false;
            }
        }
        return any;
    }

    /**
     * Real progress: bound stock already queued, a run armed, or waiting on machine output.
     *
     * <p>Deliberately ignores {@code requestedThisRun} alone - that flag is also set when
     * a job is merely blocked on an upstream craft, which is still safe to drop if the
     * finished item shows up in a provider.
     */
    private static boolean hasStarted(Job job) {
        return job.phase != CraftJobPolicy.Phase.GATHER
                || job.armedThisRun
                || hasPulledBoundStock(job);
    }

    /** True once any plan-bound provider pull for this job has been handed to the send queue. */
    private static boolean hasPulledBoundStock(Job job) {
        int planned = 0;
        for (RequestPlan.Sourced<PipeNodeId, ItemResource> input : job.inputs) {
            if (input.origin() instanceof RequestPlan.Origin.Stock) {
                planned += input.amount();
            }
        }
        if (planned <= 0) {
            return false;
        }
        int left = 0;
        for (int remaining : job.stockRemaining.values()) {
            left += Math.max(0, remaining);
        }
        return left < planned;
    }

    /**
     * Cancels jobs that cannot continue after {@code pipe} was removed.
     *
     * <p>Only the crafter, requester, or an upstream/downstream craft endpoint being broken
     * fails a job immediately. Breaking an unrelated transport pipe on the same network
     * must not dump pattern-table buffers - in-flight parcels reseat themselves if their
     * path still exists.
     *
     * @return how many jobs were cancelled
     */
    public int cancelInvolving(ServerLevel level, PipeNetwork network, BlockPos pipe) {
        if (jobs.isEmpty()) {
            return 0;
        }
        PipeNodeId target = PipeNodeId.of(level, pipe);
        long gameTime = level.getGameTime();
        int cancelled = 0;
        Iterator<Job> iterator = jobs.iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            if (!job.endpointRemoved(target)) {
                continue;
            }
            recordFailure(job.crafter, job.output,
                    target.equals(job.crafter)
                            ? "the crafting pipe was removed"
                            : "pipe at " + shortPos(target.pos()) + " was removed from the chain",
                    gameTime);
            fail(level, network, job);
            iterator.remove();
            cancelled++;
        }
        return cancelled;
    }

    /**
     * Cancels one job by id, as requested from the Autocraft Monitor.
     *
     * <p>Jobs no longer expire on a timer, so this is how a stuck craft is cleared. The
     * usual failure path runs, returning this job's ingredients and leaving the machine's
     * own contents alone.
     *
     * @return true if a job with that id was running
     */
    public boolean cancel(ServerLevel level, PipeNetwork network, long jobId) {
        Iterator<Job> iterator = jobs.iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            if (job.id != jobId) {
                continue;
            }
            recordFailure(job.crafter, job.output, "cancelled from the monitor",
                    level.getGameTime());
            fail(level, network, job);
            iterator.remove();
            return true;
        }
        return false;
    }

    public void tick(ServerLevel level, PipeNetwork network) {
        if (jobs.isEmpty()) {
            failures.removeIf(f -> level.getGameTime() > f.expires);
            return;
        }
        long gameTime = level.getGameTime();
        failures.removeIf(f -> gameTime > f.expires);
        Iterator<Job> iterator = jobs.iterator();
        while (iterator.hasNext()) {
            Job job = iterator.next();
            ServerLevel crafterLevel = levelOf(level, job.crafter);
            PipeNetwork crafterNetwork = networkOf(level, job.crafter);
            // An unloaded crafter is waiting, not gone. Reading it blind would both force
            // its chunk in and, on the tick after a world load, report every restored job as
            // having no crafting pipe - deleting the queue we just restored.
            if (crafterLevel != null && !crafterLevel.hasChunkAt(job.crafter.pos())) {
                continue;
            }
            if (crafterLevel == null || crafterNetwork == null
                    || !(crafterLevel.getBlockEntity(job.crafter.pos()) instanceof CraftingPipeBlockEntity be)
                    || be.pattern().isEmpty()) {
                if (crafterLevel != null) {
                    CraftParticles.emit(crafterLevel, job.crafter.pos(), CraftParticles.State.FAILED);
                }
                recordFailure(job.crafter, job.output,
                        "no crafting pipe or no pattern here", gameTime);
                fail(level, network, job);
                iterator.remove();
                continue;
            }
            CraftPattern pattern = be.pattern();
            if (pattern.hasSatellite()
                    && !SatelliteLookup.isReachable(
                            crafterLevel, crafterNetwork, job.crafter.pos(), pattern.satellite())) {
                CraftParticles.emit(crafterLevel, job.crafter.pos(), CraftParticles.State.FAILED);
                recordFailure(job.crafter, job.output,
                        "satellite '" + pattern.satellite() + "' not reachable", gameTime);
                fail(level, network, job);
                iterator.remove();
                continue;
            }

            // Oldest job for this crafter only (identity, not list index - removals shift indices).
            if (!isOldestForCrafter(job)) {
                continue;
            }

            Map<String, BlockPos> satellites =
                    SatelliteLookup.findAll(crafterLevel, crafterNetwork, job.crafter.pos());
            flushHeld(crafterLevel, crafterNetwork, job, satellites);
            // Nothing left to deliver means nothing left to make. Runs remaining is a
            // budget, not an obligation: once every downstream claim and the requester's
            // share are shipped, continuing would pull more ingredients and craft into
            // surplus long after the request finished.
            if (!job.owesAnything()) {
                completeJob(crafterLevel, crafterNetwork, job, pattern, satellites);
                iterator.remove();
                continue;
            }

            String severed = severedLink(crafterLevel, network, job);
            if (severed != null) {
                CraftParticles.emit(crafterLevel, job.crafter.pos(), CraftParticles.State.FAILED);
                recordFailure(job.crafter, job.output, severed, gameTime);
                fail(level, network, job);
                iterator.remove();
                continue;
            }

            if (missingSatelliteDest(pattern, satellites)) {
                CraftParticles.emit(crafterLevel, job.crafter.pos(), CraftParticles.State.FAILED);
                recordFailure(job.crafter, job.output,
                        "an ingredient names a satellite that was not found", gameTime);
                fail(level, network, job);
                iterator.remove();
                continue;
            }

            if (CraftParticles.shouldEmit(gameTime)) {
                CraftParticles.emit(crafterLevel, job.crafter.pos(), particleState(job));
            }

            if (job.phase == CraftJobPolicy.Phase.GATHER) {
                // Fill as many run-sets as the buffer can hold before arming, so crafts
                // like torches are not serialized behind one stick+dust at a time.
                if (ensureInputs(crafterLevel, crafterNetwork, job, pattern, satellites)) {
                    job.requestedThisRun = true;
                }
                boolean gatherDone = gatherComplete(crafterLevel, job, pattern, satellites);
                boolean outReady = outputReady(crafterLevel, job, pattern);
                CraftJobPolicy.GatherDecision decision = CraftJobPolicy.onGatherTick(
                        job.armedThisRun, gatherDone, outReady, job.requestedThisRun);
                job.armedThisRun = decision.armedThisRun();
                job.phase = decision.phase();
                if (job.armedThisRun || gatherDone) {
                } else if (hasInboundIngredients(crafterNetwork, job, pattern, satellites)) {
                    // Parcels still in flight count as progress so slow providers do not
                    // trip the soft stall timeout mid-delivery.
                }
            }

            if (job.phase == CraftJobPolicy.Phase.WAIT_OUTPUT) {
                boolean outReady = outputReady(crafterLevel, job, pattern);
                // Keep stuffing ingredients while the current craft finishes, but do not
                // re-pull when leftover batch output already covers the next extract
                // (1 log -> 4 planks with ghost count 1).
                if (!outReady && ensureInputs(crafterLevel, crafterNetwork, job, pattern, satellites)) {
                }
                if (!tryExtractOutput(crafterLevel, crafterNetwork, job, pattern, satellites)) {
                    continue;
                }
                job.runsRemaining -= Math.max(1, job.outputLeftRuns);
                job.outputLeftRuns = 0;
                if (job.runsRemaining <= 0) {
                    if (!CraftJobPolicy.mayCloseJob(job.runsRemaining, !job.held.isEmpty())) {
                        // Still holding output for a consumer that had no room. Stay alive;
                        // flushHeld retries every tick and the job closes once it is empty.
                        continue;
                    }
                    completeJob(crafterLevel, crafterNetwork, job, pattern, satellites);
                    iterator.remove();
                    continue;
                }
                outReady = outputReady(crafterLevel, job, pattern);
                CraftJobPolicy.RunCompleteDecision next =
                        CraftJobPolicy.afterRunComplete(job.runsRemaining, outReady);
                job.phase = next.phase();
                job.armedThisRun = next.armedThisRun();
            }
        }
    }

    /**
     * Stops further ingredient pulls and routes leftovers to the default route.
     *
     * <p>Batch gather can leave extra ingredients queued, in flight, or sitting in the
     * pattern table after the request is already satisfied - those must not spill on
     * the floor when a default route exists.
     */
    private void completeJob(ServerLevel level,
                             PipeNetwork network,
                             Job job,
                             CraftPattern pattern,
                             Map<String, BlockPos> satellites) {
        releaseHeld(level, network, job);
        cancelIngredientInbound(level, network, job, pattern, satellites);
        dumpJobBuffers(level, network, job, pattern, satellites);
    }

    /**
     * Cancels queued and in-flight ingredient pulls for this job's buffers.
     *
     * <p>In-flight parcels are reclaimed toward the default route (not dropped).
     */
    private void cancelIngredientInbound(ServerLevel level,
                                         PipeNetwork network,
                                         Job job,
                                         CraftPattern pattern,
                                         Map<String, BlockPos> satellites) {
        Optional<Map<BufferKey, Integer>> demands = bufferNeeds(job, pattern, satellites, 1);
        if (demands.isEmpty()) {
            return;
        }
        for (BufferKey key : demands.get().keySet()) {
            cancelToward(level, key.dest(), key.item());
        }
    }

    /**
     * Returns this job's ingredients from the crafter and satellite buffers.
     *
     * <p>Scoped to the pattern's own ingredients, so a machine keeps its fuel and its
     * finished product. A crafting pipe drives a machine it does not own.
     */
    private void dumpJobBuffers(ServerLevel level,
                                PipeNetwork network,
                                Job job,
                                CraftPattern pattern,
                                Map<String, BlockPos> satellites) {
        Optional<Map<BufferKey, Integer>> demands =
                bufferNeeds(job, pattern, satellites, Math.max(1, job.runsRemaining));
        if (demands.isEmpty()) {
            return;
        }
        for (Map.Entry<BufferKey, Integer> demand : demands.get().entrySet()) {
            BufferKey key = demand.getKey();
            ServerLevel destLevel = levelOf(level, key.dest());
            PipeNetwork destNetwork = networkOf(level, key.dest());
            if (destLevel == null || destNetwork == null) {
                continue;
            }
            int held = Math.min(demand.getValue(),
                    InventoryAccess.count(destLevel, key.dest().pos(), key.item()));
            if (held > 0) {
                routeOrDrop(destLevel, destNetwork, key.dest().pos(), key.item(), held);
            }
        }
    }

    /**
     * Human readable state of every job, for the debug overlay.
     *
     * <p>Reports what each pipe is waiting on rather than only that it is waiting, since a
     * job blocked on an upstream crafter and a job whose ingredient does not exist look
     * identical in game.
     */
    public List<JobReport> describe(ServerLevel level, PipeNetwork network) {
        List<JobReport> out = new ArrayList<>(jobs.size() + failures.size());
        for (Failure failure : failures) {
            out.add(new JobReport(
                    failure.crafter,
                    "FAILED " + itemName(failure.output),
                    List.of(failure.reason)));
        }
        for (Job job : jobs) {
            List<String> detail = new ArrayList<>();
            String output = itemName(job.output);
            String headline = output + " x" + job.runsRemaining + " runs, " + job.phase;

            ServerLevel crafterLevel = levelOf(level, job.crafter);
            PipeNetwork crafterNetwork = networkOf(level, job.crafter);
            if (crafterLevel != null && !crafterLevel.hasChunkAt(job.crafter.pos())) {
                // Waiting for its chunk, not broken - and saying so beats force-loading it
                // just to fill in a debug line.
                detail.add("crafter's chunk is not loaded");
                out.add(new JobReport(job.crafter.pos(), headline + " (UNLOADED)", detail));
                continue;
            }
            if (crafterLevel == null || crafterNetwork == null
                    || !(crafterLevel.getBlockEntity(job.crafter.pos()) instanceof CraftingPipeBlockEntity be)
                    || be.pattern().isEmpty()) {
                detail.add("no pattern on this pipe");
                out.add(new JobReport(job.crafter.pos(), headline + " (BROKEN)", detail));
                continue;
            }
            CraftPattern pattern = be.pattern();
            Map<String, BlockPos> satellites =
                    SatelliteLookup.findAll(crafterLevel, crafterNetwork, job.crafter.pos());

            if (!isOldestForCrafter(job)) {
                // Waiting for the crafter to free up is not the same as being stuck, and
                // they look identical without saying so.
                detail.add("queued behind another request on this crafter");
            }
            if (hasPendingDependency(job)) {
                for (PipeNodeId upstream : job.dependsOn) {
                    detail.add("blocked on crafter " + shortPos(upstream.pos()));
                }
            }

            if (job.phase == CraftJobPolicy.Phase.WAIT_OUTPUT
                    && InventoryAccess.visibleOnlyFromAnotherFace(
                            crafterLevel, job.crafter.pos(), job.output)) {
                detail.add("output exists but this face cannot reach it; a furnace only"
                        + " exposes its result slot underneath");
            }
            Optional<Map<BufferKey, Integer>> perRun = bufferNeeds(job, pattern, satellites, 1);
            if (perRun.isEmpty()) {
                detail.add("a satellite for this pattern cannot be resolved");
            } else {
                for (Map.Entry<BufferKey, Integer> need : perRun.get().entrySet()) {
                    BufferKey key = need.getKey();
                    ServerLevel destLevel = levelOf(level, key.dest());
                    int have = destLevel == null
                            ? 0
                            : InventoryAccess.count(destLevel, key.dest().pos(), key.item());
                    int inbound = inboundTo(crafterNetwork, key.dest(), key.item());
                    String where = key.dest().equals(job.crafter)
                            ? ""
                            : " at " + shortPos(key.dest().pos());
                    String origin = job.awaitsCraft(key.item()) ? " (from craft)" : " (from stock)";
                    detail.add(itemName(key.item()) + where + ": have " + have
                            + "/" + need.getValue() + ", inbound " + inbound + origin);
                }
            }

            for (Map.Entry<StockBinding, Integer> left : job.stockRemaining.entrySet()) {
                if (left.getValue() > 0) {
                    detail.add("still to pull " + left.getValue() + " "
                            + itemName(left.getKey().item()) + " from "
                            + shortPos(left.getKey().provider().pos()));
                }
            }
            for (Owed owed : job.owes) {
                if (owed.remaining > 0) {
                    detail.add("owes " + owed.remaining + " " + itemName(owed.item)
                            + " to " + shortPos(owed.consumer.pos()));
                }
            }
            if (job.remainingForRequester > 0) {
                detail.add("owes " + job.remainingForRequester + " " + output
                        + " to requester " + shortPos(job.requester.pos()));
            }
            out.add(new JobReport(job.crafter.pos(), headline, detail));
        }
        return out;
    }

    /** One job's state in words, for the chat command. */
    public record JobReport(BlockPos crafter, String headline, List<String> detail) {
    }

    /**
     * Cards for the Autocraft Monitor UI: this manager's jobs whose crafter is in
     * {@code component}, plus recent failures there.
     *
     * <p>Returns cards rather than a finished payload because one manager is never the whole
     * answer. A job is queued on the <em>requester's</em> level, so a craft ordered in the
     * overworld that runs on a nether crafting pipe lives in the overworld manager and would
     * be invisible to a monitor on either side if each only asked its own level. The caller
     * sweeps every network and merges.
     */
    public List<CraftMonitorPayload.Card> monitorCards(ServerLevel level,
                                                       PipeNetwork network,
                                                       Set<PipeNodeId> component,
                                                       long gameTime) {
        List<CraftMonitorPayload.Card> cards = new ArrayList<>();
        for (Failure failure : failures) {
            if (!component.contains(PipeNodeId.of(failure.dimension, failure.crafter))) {
                continue;
            }
            ItemStack out = failure.output.toStack(1);
            cards.add(new CraftMonitorPayload.Card(
                    out.isEmpty() ? ItemStack.EMPTY : out,
                    failure.crafter,
                    CraftMonitorPayload.Status.FAILED,
                    0,
                    0L,
                    failure.reason,
                    List.of()));
        }
        for (Job job : jobs) {
            // Matched on the full node, not a bare position: the component now spans
            // dimensions, and two dimensions can hold a crafter at the same coordinates.
            if (!component.contains(job.crafter)) {
                continue;
            }
            CraftMonitorPayload.Status status;
            String detail;
            if (!isOldestForCrafter(job)) {
                status = CraftMonitorPayload.Status.QUEUED;
                detail = "Queued behind another job on this crafter";
            } else if (hasPendingDependency(job)) {
                status = CraftMonitorPayload.Status.GATHER;
                detail = "Waiting on upstream crafts";
            } else if (job.phase == CraftJobPolicy.Phase.WAIT_OUTPUT) {
                status = CraftMonitorPayload.Status.WAIT_OUTPUT;
                ServerLevel crafterLevel = levelOf(level, job.crafter);
                detail = crafterLevel != null
                        && InventoryAccess.visibleOnlyFromAnotherFace(
                                crafterLevel, job.crafter.pos(), job.output)
                        ? "Output is made but not reachable from this face; move the pipe"
                        : "Waiting for craft output";
            } else {
                status = CraftMonitorPayload.Status.GATHER;
                detail = "Gathering ingredients";
            }

            List<CraftMonitorPayload.Want> wants = new ArrayList<>();
            ServerLevel crafterLevel = levelOf(level, job.crafter);
            PipeNetwork crafterNetwork = networkOf(level, job.crafter);
            if (crafterLevel != null && crafterNetwork != null
                    && crafterLevel.getBlockEntity(job.crafter.pos()) instanceof CraftingPipeBlockEntity be
                    && !be.pattern().isEmpty()) {
                Map<String, BlockPos> satellites =
                        SatelliteLookup.findAll(crafterLevel, crafterNetwork, job.crafter.pos());
                Optional<Map<BufferKey, Integer>> perRun =
                        bufferNeeds(job, be.pattern(), satellites, 1);
                if (perRun.isPresent()) {
                    for (Map.Entry<BufferKey, Integer> need : perRun.get().entrySet()) {
                        BufferKey key = need.getKey();
                        ServerLevel destLevel = levelOf(level, key.dest());
                        int have = destLevel == null
                                ? 0
                                : InventoryAccess.count(destLevel, key.dest().pos(), key.item());
                        int inbound = inboundTo(crafterNetwork, key.dest(), key.item());
                        int missing = need.getValue() - have - inbound;
                        if (missing <= 0) {
                            continue;
                        }
                        ItemStack stack = key.item().toStack(Math.min(missing, 99));
                        if (!stack.isEmpty()) {
                            wants.add(new CraftMonitorPayload.Want(
                                    stack, job.awaitsCraft(key.item())));
                        }
                    }
                }
            }

            ItemStack out = job.output.toStack(1);
            cards.add(new CraftMonitorPayload.Card(
                    out.isEmpty() ? ItemStack.EMPTY : out,
                    job.crafter.pos(),
                    status,
                    job.runsRemaining,
                    job.id,
                    detail,
                    wants));
        }
        return cards;
    }

    /**
     * Outstanding craft claims in {@code component}, for the monitor's order queue.
     *
     * <p>Both the downstream {@link Owed} claims and what the job still owes the original
     * requester, since a delivery stuck at either end looks identical from in front of a
     * machine that is not running.
     */
    public List<CraftMonitorPayload.Order> orderRows(ServerLevel any, Set<PipeNodeId> component) {
        List<CraftMonitorPayload.Order> rows = new ArrayList<>();
        for (Job job : jobs) {
            if (!component.contains(job.crafter)) {
                continue;
            }
            ServerLevel crafterLevel = levelOf(any, job.crafter);
            ItemStack machine = crafterLevel == null
                    ? ItemStack.EMPTY
                    : InventoryAccess.attachedBlockIcon(crafterLevel, job.crafter.pos());
            for (Owed owed : job.owes) {
                if (owed.remaining <= 0) {
                    continue;
                }
                ItemStack stack = owed.item.toStack(1);
                if (!stack.isEmpty()) {
                    rows.add(new CraftMonitorPayload.Order(stack, job.crafter.pos(),
                            owed.consumer.pos(), owed.remaining, true, machine));
                }
            }
            if (job.remainingForRequester > 0) {
                ItemStack stack = job.output.toStack(1);
                if (!stack.isEmpty()) {
                    rows.add(new CraftMonitorPayload.Order(stack, job.crafter.pos(),
                            job.requester.pos(), job.remainingForRequester, true, machine));
                }
            }
        }
        return rows;
    }

    /**
     * What each crafting pipe is still short of, for the in-world hologram.
     *
     * <p>Only unmet ingredients are reported, so a pipe that has everything it needs shows
     * nothing and a stalled one shows exactly what it is waiting on.
     */
    public List<CraftStatusPayload.Entry> holograms(ServerLevel level, PipeNetwork network) {
        List<CraftStatusPayload.Entry> out = new ArrayList<>();
        for (Job job : jobs) {
            if (!job.crafter.dimension().equals(level.dimension())) {
                continue;
            }
            ServerLevel crafterLevel = levelOf(level, job.crafter);
            PipeNetwork crafterNetwork = networkOf(level, job.crafter);
            // Nothing to draw over an unloaded chunk, and nobody to see it.
            if (crafterLevel == null || crafterNetwork == null
                    || !crafterLevel.hasChunkAt(job.crafter.pos())
                    || !(crafterLevel.getBlockEntity(job.crafter.pos()) instanceof CraftingPipeBlockEntity be)
                    || be.pattern().isEmpty()) {
                continue;
            }
            Map<String, BlockPos> satellites =
                    SatelliteLookup.findAll(crafterLevel, crafterNetwork, job.crafter.pos());
            Optional<Map<BufferKey, Integer>> perRun = bufferNeeds(job, be.pattern(), satellites, 1);
            if (perRun.isEmpty()) {
                continue;
            }
            List<CraftStatusPayload.Want> wants = new ArrayList<>();
            for (Map.Entry<BufferKey, Integer> need : perRun.get().entrySet()) {
                BufferKey key = need.getKey();
                ServerLevel destLevel = levelOf(level, key.dest());
                int have = destLevel == null
                        ? 0
                        : InventoryAccess.count(destLevel, key.dest().pos(), key.item());
                int inbound = inboundTo(crafterNetwork, key.dest(), key.item());
                int missing = need.getValue() - have - inbound;
                if (missing <= 0) {
                    continue;
                }
                ItemStack stack = key.item().toStack(Math.min(missing, 99));
                if (stack.isEmpty()) {
                    continue;
                }
                wants.add(new CraftStatusPayload.Want(stack, job.awaitsCraft(key.item())));
            }
            if (!wants.isEmpty()) {
                out.add(new CraftStatusPayload.Entry(job.crafter.pos(), wants));
            }
        }
        return out;
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String itemName(ItemResource item) {
        return item.isEmpty() ? "empty" : item.toStack(1).getHoverName().getString();
    }

    /**
     * What this job should be showing the player right now.
     *
     * <p>Waiting on an upstream crafter is reported separately from waiting on ingredients,
     * because they look identical in game and mean very different things.
     */
    private CraftParticles.State particleState(Job job) {
        if (hasPendingDependency(job)) {
            return CraftParticles.State.BLOCKED_UPSTREAM;
        }
        return job.phase == CraftJobPolicy.Phase.WAIT_OUTPUT
                ? CraftParticles.State.CRAFTING
                : CraftParticles.State.GATHERING;
    }

    /**
     * Why this job can no longer reach the rest of its chain, or null while it still can.
     *
     * <p>Breaking a pipe mid chain otherwise leaves every job downstream of the break
     * waiting forever on deliveries that can never arrive, holding provider stock
     * reserved the whole time.
     *
     * <p>Deliberately conservative. The routing snapshot only ever covers the component it
     * was last rebuilt around, so a job elsewhere in the level legitimately will not appear
     * in it. Absence is treated as "unknown, leave alone"; only a positively observed
     * break fails the job.
     */
    private static String severedLink(ServerLevel crafterLevel, PipeNetwork network, Job job) {
        // A missing pipe is definitive and needs no routing table to confirm.
        if (!(crafterLevel.getBlockState(job.crafter.pos()).getBlock()
                instanceof com.bobby.bobbypipes.block.PipeBlock)) {
            return "the crafting pipe was removed";
        }
        // Deliberately conservative: only fail when both ends are visible to routing and
        // still cannot reach each other. Temporary bridge/rebuild gaps must not dump jobs.
        if (!job.requester.equals(job.crafter)
                && positivelyDisconnected(network, job.crafter, job.requester)) {
            return "the requester at " + shortPos(job.requester.pos()) + " is no longer connected";
        }
        for (PipeNodeId upstream : job.dependsOn) {
            if (positivelyDisconnected(network, upstream, job.crafter)) {
                return "upstream crafter " + shortPos(upstream.pos()) + " is no longer connected";
            }
        }
        for (Owed owed : job.owes) {
            if (owed.remaining > 0
                    && positivelyDisconnected(network, job.crafter, owed.consumer)) {
                return "downstream crafter " + shortPos(owed.consumer.pos())
                        + " is no longer connected";
            }
        }
        return null;
    }

    /**
     * True only when routing can see both ends and still reports no path. Missing from
     * the snapshot or bridge is treated as unknown (not severed).
     */
    private static boolean positivelyDisconnected(PipeNetwork network,
                                                  PipeNodeId from,
                                                  PipeNodeId to) {
        if (from.equals(to)) {
            return false;
        }
        // Ask the links first. They can join two places local routing sees as separate,
        // including two components in the same dimension when the chain hops out through
        // another world and back. Judging that shape on the local snapshot alone declared
        // every job severed the moment it started, which is what failed a whole bank of
        // crafters at once.
        if (CrossDimPipeGraph.canReach(from, to)) {
            return false;
        }
        if (from.sameDimension(to) && from.dimension().equals(network.level().dimension())) {
            RoutingSnapshot<BlockPos> routes = network.routes();
            return routes.contains(from.pos())
                    && routes.contains(to.pos())
                    && !routes.canReach(from.pos(), to.pos());
        }
        boolean sawBoth = false;
        for (RoutingSnapshot<PipeNodeId> bridge : CrossDimPipeGraph.bridges()) {
            if (!bridge.contains(from) || !bridge.contains(to)) {
                continue;
            }
            sawBoth = true;
            if (bridge.canReach(from, to)) {
                return false;
            }
        }
        return sawBoth;
    }

    /** True while any crafter this job depends on still has an unfinished job. */
    private boolean hasPendingDependency(Job job) {
        if (job.dependsOn.isEmpty()) {
            return false;
        }
        for (Job other : jobs) {
            if (other != job && job.dependsOn.contains(other.crafter)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOldestForCrafter(Job job) {
        for (Job other : jobs) {
            if (other.crafter.equals(job.crafter)) {
                return other == job;
            }
        }
        return false;
    }

    private static boolean missingSatelliteDest(CraftPattern pattern, Map<String, BlockPos> satellites) {
        for (CraftPattern.CountedIngredient ingredient : pattern.ingredients()) {
            if (ingredient.satellite() == null || ingredient.satellite().isBlank()) {
                continue;
            }
            if (!satellites.containsKey(ingredient.satellite())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cap so an absurd request (a million-x order) still fills the buffer in waves instead
     * of one giant flood. In practice this rarely binds: {@link CraftJobPolicy#gatherBatchRuns}
     * already limits runs to what {@code have + insertable} can physically hold, so raising
     * this just lets ordinary large orders (dozens to a couple hundred runs of an
     * intermediate) gather in one wave instead of several.
     */
    private static final int MAX_GATHER_BATCH_RUNS = 256;

    /**
     * Longest a job will sit on finished output waiting for the rest of a batch.
     *
     * <p>Half a second still covers the case batching exists for - a crafter producing a run
     * per tick fills its output slot long before this - while being short enough not to stack
     * up noticeably across a multi-step craft chain, where every link pays this once.
     */
    private static final long OUTPUT_ACCUMULATE_TICKS = 10L;

    /**
     * Orders whatever this job is still short of for as many runs as the buffers can fit.
     *
     * @return true if this job is now legitimately waiting on the network, either because
     *         it enqueued a pull or because an upstream craft owes it an ingredient.
     *         Both mean "work is in progress", which is what lets a finished output be
     *         claimed. Returning false only for craft-fed jobs left them stuck in GATHER
     *         with their result sitting in the table.
     */
    private boolean ensureInputs(ServerLevel level,
                                 PipeNetwork network,
                                 Job job,
                                 CraftPattern pattern,
                                 Map<String, BlockPos> satellites) {
        boolean requested = false;
        int runs = gatherBatchRuns(level, network, job, pattern, satellites);
        if (runs <= 0) {
            return false;
        }
        Optional<Map<BufferKey, Integer>> demands = bufferNeeds(job, pattern, satellites, runs);
        if (demands.isEmpty()) {
            return false;
        }
        for (Map.Entry<BufferKey, Integer> demand : demands.get().entrySet()) {
            BufferKey key = demand.getKey();
            ServerLevel destLevel = levelOf(level, key.dest());
            int have = destLevel == null
                    ? 0
                    : InventoryAccess.count(destLevel, key.dest().pos(), key.item());
            int inbound = inboundTo(network, key.dest(), key.item());
            int need = CraftJobPolicy.pullNeed(demand.getValue(), have, inbound);
            if (need <= 0) {
                continue;
            }
            // Stock and craft can both bind the same item (take shelf first, craft the
            // rest). Only skip the craft-sourced remainder - never skip remaining stock.
            int fromStock = Math.min(need, job.stockLeft(key.item()));
            if (fromStock > 0 && pullBound(level, job, key.dest(), key.item(), fromStock) > 0) {
                requested = true;
                need -= fromStock;
            }
            if (need > 0 && job.awaitsCraft(key.item())) {
                // Upstream craft still owes the rest; do not re-query live supply.
                requested = true;
            }
        }
        return requested;
    }

    /**
     * Runs to target this gather: min(remaining, buffer capacity, {@link #MAX_GATHER_BATCH_RUNS}).
     */
    private static int gatherBatchRuns(ServerLevel level,
                                       PipeNetwork network,
                                       Job job,
                                       CraftPattern pattern,
                                       Map<String, BlockPos> satellites) {
        Optional<Map<BufferKey, Integer>> perRun = bufferNeeds(job, pattern, satellites, 1);
        if (perRun.isEmpty() || perRun.get().isEmpty()) {
            return 0;
        }
        int size = perRun.get().size();
        int[] needs = new int[size];
        int[] have = new int[size];
        int[] insertable = new int[size];
        int i = 0;
        for (Map.Entry<BufferKey, Integer> entry : perRun.get().entrySet()) {
            BufferKey key = entry.getKey();
            int per = Math.max(0, entry.getValue());
            needs[i] = per;
            ServerLevel destLevel = levelOf(level, key.dest());
            if (destLevel == null) {
                have[i] = 0;
                insertable[i] = 0;
            } else {
                have[i] = InventoryAccess.count(destLevel, key.dest().pos(), key.item());
                int probe = Math.max(per, per * Math.min(job.runsRemaining, MAX_GATHER_BATCH_RUNS));
                insertable[i] = InventoryAccess.insertable(
                        destLevel, key.dest().pos(), key.item(), probe);
            }
            i++;
        }
        return CraftJobPolicy.gatherBatchRuns(
                job.runsRemaining, needs, have, insertable, MAX_GATHER_BATCH_RUNS);
    }

    private static boolean gatherComplete(ServerLevel level,
                                          Job job,
                                          CraftPattern pattern,
                                          Map<String, BlockPos> satellites) {
        Optional<Map<BufferKey, Integer>> demands = bufferNeeds(job, pattern, satellites, 1);
        if (demands.isEmpty()) {
            return false;
        }
        for (Map.Entry<BufferKey, Integer> demand : demands.get().entrySet()) {
            BufferKey key = demand.getKey();
            ServerLevel destLevel = levelOf(level, key.dest());
            if (destLevel == null
                    || InventoryAccess.count(destLevel, key.dest().pos(), key.item())
                    < demand.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** True when any per-run ingredient still has queued or flying parcels toward its buffer. */
    private static boolean hasInboundIngredients(PipeNetwork network,
                                                 Job job,
                                                 CraftPattern pattern,
                                                 Map<String, BlockPos> satellites) {
        Optional<Map<BufferKey, Integer>> demands = bufferNeeds(job, pattern, satellites, 1);
        if (demands.isEmpty()) {
            return false;
        }
        for (BufferKey key : demands.get().keySet()) {
            if (inboundTo(network, key.dest(), key.item()) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Per-run ingredient totals keyed by buffer destination + item.
     *
     * <p>Empty when any satellite-routed ingredient cannot be resolved.
     */
    private static Optional<Map<BufferKey, Integer>> bufferNeeds(Job job,
                                                                 CraftPattern pattern,
                                                                 Map<String, BlockPos> satellites,
                                                                 int runs) {
        Map<BufferKey, Integer> totals = new java.util.LinkedHashMap<>();
        int scale = Math.max(1, runs);
        for (CraftPattern.CountedIngredient ingredient : pattern.ingredients()) {
            Optional<BlockPos> destPos = CraftJobPolicy.resolveDest(
                    job.crafter.pos(), ingredient.satellite(), satellites);
            if (destPos.isEmpty()) {
                return Optional.empty();
            }
            totals.merge(
                    new BufferKey(
                            PipeNodeId.of(job.crafter.dimension(), destPos.get()),
                            ingredient.item()),
                    ingredient.count() * scale, Integer::sum);
        }
        return Optional.of(totals);
    }

    private static boolean outputReady(ServerLevel level, Job job, CraftPattern pattern) {
        List<CraftPattern.CountedIngredient> results = pattern.results();
        if (results.isEmpty()) {
            return false;
        }
        for (CraftPattern.CountedIngredient result : results) {
            if (InventoryAccess.count(level, job.crafter.pos(), result.item()) < result.count()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Pulls from the providers the plan bound to this job.
     *
     * <p>Deliberately not a fresh supply query. Asking the network again would find nothing
     * for an intermediate, because the item that will satisfy it has not been crafted yet,
     * and would also let two jobs claim the same stock.
     *
     * @return how much was actually enqueued
     */
    private static int pullBound(ServerLevel any,
                                 Job job,
                                 PipeNodeId dest,
                                 ItemResource item,
                                 int need) {
        int remaining = need;
        for (Map.Entry<StockBinding, Integer> entry : job.stockRemaining.entrySet()) {
            if (remaining <= 0) {
                break;
            }
            StockBinding binding = entry.getKey();
            if (!binding.item().equals(item) || entry.getValue() <= 0) {
                continue;
            }
            PipeNetwork providerNetwork = networkOf(any, binding.provider());
            if (providerNetwork == null) {
                continue;
            }
            int take = Math.min(remaining, entry.getValue());
            providerNetwork.sendQueue().enqueueToward(
                    binding.provider().pos(), dest, item, take, Set.of());
            entry.setValue(entry.getValue() - take);
            remaining -= take;
        }
        return need - remaining;
    }

    /**
     * Items physically on their way to {@code dest}: queued withdrawals and flying parcels.
     *
     * <p>Deliberately excludes craft-owed output, unlike {@link #inboundTo}. See
     * {@link CraftJobPolicy#roomFor} for why counting it deadlocked the last delivery of a
     * craft.
     */
    private static int enRouteTo(PipeNodeId dest, ItemResource item) {
        int queued = 0;
        int flying = 0;
        for (PipeNetwork net : PipeNetwork.instances()) {
            queued += net.sendQueue().queuedToward(dest, item);
            flying += net.flyingItemsToward(dest, item);
        }
        return Math.max(0, queued) + Math.max(0, flying);
    }

    static int inboundTo(PipeNetwork hint, PipeNodeId dest, ItemResource item) {
        int queued = 0;
        int flying = 0;
        int owed = 0;
        for (PipeNetwork net : PipeNetwork.instances()) {
            queued += net.sendQueue().queuedToward(dest, item);
            flying += net.flyingItemsToward(dest, item);
            owed += net.craftJobs().owedTo(dest, item);
        }
        return CraftJobPolicy.inbound(queued, flying, owed);
    }

    /**
     * How many of {@code item} active jobs still intend to deliver to {@code dest}.
     *
     * <p>Counts requester claims only. Downstream crafter {@link Owed} feeds go to a
     * buffer that may be a satellite, so they are tracked separately by gather.
     */
    int owedTo(PipeNodeId dest, ItemResource item) {
        if (item.isEmpty() || jobs.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Job job : jobs) {
            if (job.requester.equals(dest) && job.output.equals(item)) {
                total += Math.max(0, job.remainingForRequester);
            }
        }
        return total;
    }

    /**
     * Extracts a batch of finished runs at the shared pipe extract rate, then ships each
     * chunk.
     *
     * <p>Does not ship the moment one run is ready - a fast auto-crafter producing one run
     * a tick would otherwise fire single-item parcels onto the pipe every tick. Instead lets
     * runs pile up in the crafter's own output slot (see {@link CraftJobPolicy#extractBatchRuns})
     * and takes a whole batch at once, still dripped out at the shared extract pulse rate so
     * a big batch does not dump onto the network in one tick either. The batch target is the
     * output slot's own stack capacity, so this can never leave the crafter jammed: it always
     * either reaches that cap or, if the input buffer runs dry first, flushes immediately.
     *
     * @return true when the current batch has been fully extracted and shipped
     */
    private boolean tryExtractOutput(ServerLevel level,
                                     PipeNetwork network,
                                     Job job,
                                     CraftPattern pattern,
                                     Map<String, BlockPos> satellites) {
        List<CraftPattern.CountedIngredient> results = pattern.results();
        if (results.isEmpty()) {
            return false;
        }

        if (job.outputLeft == null) {
            int available = availableRuns(level, job.crafter.pos(), results);
            if (available <= 0) {
                // Nothing to accumulate yet, so the batch window has not opened.
                job.outputPendingSince = -1L;
                return false;
            }
            if (job.outputPendingSince < 0L) {
                job.outputPendingSince = level.getGameTime();
            }
            boolean moreComing = gatherComplete(level, job, pattern, satellites);
            int runs = CraftJobPolicy.extractBatchRuns(
                    available, job.runsRemaining, outputCapRuns(results), moreComing,
                    level.getGameTime() - job.outputPendingSince, OUTPUT_ACCUMULATE_TICKS);
            if (runs <= 0) {
                return false;
            }
            job.outputLeft = new LinkedHashMap<>();
            for (CraftPattern.CountedIngredient result : results) {
                job.outputLeft.merge(result.item(), result.count() * runs, Integer::sum);
            }
            job.outputLeftRuns = runs;
        }

        int allow = network.extractBudget().budget(job.crafter.pos(), level.getGameTime());
        if (allow <= 0) {
            return false;
        }

        for (Map.Entry<ItemResource, Integer> entry : job.outputLeft.entrySet()) {
            if (allow <= 0) {
                break;
            }
            int left = entry.getValue();
            if (left <= 0) {
                continue;
            }
            ItemResource item = entry.getKey();
            int available = InventoryAccess.count(level, job.crafter.pos(), item);
            int want = Math.min(Math.min(left, allow), available);
            // Do not pull out of the machine what cannot be handed on. Left inside, it stops
            // the machine on its own, which is the backpressure that keeps a fast crafter
            // from burying a slower consumer downstream. A pattern table is exempt: its one
            // output slot means declining to drain it stops it crafting entirely, so it is
            // emptied at the pulse rate and the overflow is held on the job.
            if (!drivesPatternTable(level, job.crafter.pos())) {
                want = Math.min(want, placeableNow(level, network, job, item, want));
            }
            if (want <= 0) {
                continue;
            }
            // Read the machine face before the extract empties it, so the parcel leaves
            // through the arm touching the machine rather than out of the pipe centre.
            Direction from = InventoryAccess.sideHolding(level, job.crafter.pos(), item).orElse(null);
            int got = InventoryAccess.extract(level, job.crafter.pos(), item, want);
            if (got <= 0) {
                continue;
            }
            network.extractBudget().consume(job.crafter.pos(), got);
            allow -= got;
            entry.setValue(left - got);
            shipOutput(level, network, job, item, got, from, satellites);
        }

        job.outputLeft.entrySet().removeIf(e -> e.getValue() <= 0);
        if (job.outputLeft.isEmpty()) {
            job.outputLeft = null;
            // Batch shipped: the next one starts its own accumulation window.
            job.outputPendingSince = -1L;
            return true;
        }
        return false;
    }

    /** Complete runs' worth of result currently sitting in the crafter. */
    private static int availableRuns(ServerLevel level,
                                      BlockPos crafter,
                                      List<CraftPattern.CountedIngredient> results) {
        int runs = Integer.MAX_VALUE;
        for (CraftPattern.CountedIngredient result : results) {
            int per = Math.max(1, result.count());
            int have = InventoryAccess.count(level, crafter, result.item());
            runs = Math.min(runs, have / per);
        }
        return runs == Integer.MAX_VALUE ? 0 : runs;
    }

    /**
     * Runs' worth of result that fit in the output slot before the crafter itself refuses
     * to make more (see {@code PatternTableBlockEntity}'s output-slot stack size check).
     * Naturally caps at 1 for non-stackable results, which just falls back to today's
     * one-at-a-time behaviour - correct, since the slot cannot hold more than that anyway.
     */
    private static int outputCapRuns(List<CraftPattern.CountedIngredient> results) {
        int runs = Integer.MAX_VALUE;
        for (CraftPattern.CountedIngredient result : results) {
            int per = Math.max(1, result.count());
            int maxStack = Math.max(1, result.item().toStack(1).getMaxStackSize());
            runs = Math.min(runs, Math.max(1, maxStack / per));
        }
        return runs == Integer.MAX_VALUE ? 1 : runs;
    }

    /**
     * How many of {@code item} {@code dest} can physically take right now.
     *
     * <p>Free space minus what is already queued or flying toward it. Gather has always done
     * this on the way in ({@link CraftJobPolicy#pullNeed} against
     * {@link InventoryAccess#insertable}); shipping did not, so a fast crafter feeding a
     * slower one downstream kept dispatching against what the plan said it owed rather than
     * what the consumer could hold. The overflow had nowhere to go and ended up on the floor.
     *
     * @param want upper bound to probe for; probing is not free, so callers pass what they
     *             actually intend to send rather than a stack
     */
    private static int acceptableAt(ServerLevel any, PipeNodeId dest, ItemResource item, int want) {
        if (want <= 0 || item.isEmpty()) {
            return 0;
        }
        ServerLevel destLevel = levelOf(any, dest);
        PipeNetwork destNetwork = networkOf(any, dest);
        if (destLevel == null || destNetwork == null) {
            return 0;
        }
        // Probe for the ask *plus* what is already on its way, then subtract the latter. The
        // probe is capped at whatever it is asked for, so probing only {@code want} and then
        // subtracting in-flight reports zero room the moment a batch is travelling - even
        // when the machine is nearly empty. EnergyRequestService hit this first and its
        // comment says the same thing; the craft path never got the same treatment, which is
        // why three smelters read "no room" on an alloy smelter holding four ingots.
        int enRoute = enRouteTo(dest, item);
        int space = InventoryAccess.insertable(
                destLevel, dest.pos(), item, saturatingAdd(want, enRoute));
        return CraftJobPolicy.roomFor(want, space, enRoute);
    }

    /** Capped add, so a huge claim plus a huge in-flight count cannot wrap negative. */
    private static int saturatingAdd(int a, int b) {
        return (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, a) + Math.max(0, b));
    }

    /**
     * True when {@code consumer} is a machine worth throttling.
     *
     * <p>Backpressure exists for exactly one situation: a third-party machine with a small
     * output that jams when it is fed faster than it works - three fast smelters feeding one.
     * It is not a general flow-control rule, and applying it everywhere caused two stalls in a
     * row. Our own pattern table takes what it is given into eighteen resource slots and gets
     * on with it, so throttling one only stopped a plank table from feeding a chest table.
     *
     * <p>The requester is not consulted at all: it is a delivery address, not a machine, and if
     * its container is full the surplus path already routes or spills.
     */
    private static boolean throttles(ServerLevel any, PipeNodeId consumer) {
        ServerLevel consumerLevel = levelOf(any, consumer);
        if (consumerLevel == null || !consumerLevel.hasChunkAt(consumer.pos())) {
            return false;
        }
        return !drivesPatternTable(consumerLevel, consumer.pos());
    }

    /**
     * True when this crafting pipe drives one of our own Pattern Tables.
     *
     * <p>A pattern table has a single output slot, so a pipe that declines to drain it stops
     * it crafting altogether. Third-party machines are throttled by downstream capacity
     * ({@link #placeableNow}); a pattern table is drained as fast as the extract pulse allows
     * and anything that cannot be placed yet is held on the job instead.
     */
    private static boolean drivesPatternTable(ServerLevel level, BlockPos crafter) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = crafter.relative(direction);
            if (level.hasChunkAt(neighbour)
                    && level.getBlockEntity(neighbour) instanceof PatternTableBlockEntity) {
                return true;
            }
        }
        return false;
    }

    /**
     * How much of {@code item} this job is still obliged to hand over.
     *
     * <p>Output covered by a claim is never surplus, however full the consumer happens to be
     * right now. Blurring the two is what put copper ingots on the floor: three smelters
     * feeding one alloy smelter found it full, concluded nobody wanted the ingots, and
     * "routed" them to a default route or the ground while still owing them.
     */
    private static int outstandingClaims(Job job, ItemResource item) {
        int claimed = 0;
        for (Owed owed : job.owes) {
            if (owed.item.equals(item) && owed.remaining > 0) {
                claimed += owed.remaining;
            }
        }
        if (item.equals(job.output) && job.remainingForRequester > 0) {
            claimed += job.remainingForRequester;
        }
        return claimed;
    }

    /**
     * How much of {@code item} this job could actually place somewhere this tick.
     *
     * <p>Mirrors the order {@link #shipOutput} settles in, so extraction never pulls more out
     * of a machine than it can hand on. When everything downstream is full this is zero and
     * the output simply stays in the machine, which is the backpressure that stops a fast
     * smelter burying a slow one. Surplus capacity is included so a batch recipe's genuine
     * leftovers can still leave for a default route rather than jamming the crafter.
     */
    private int placeableNow(ServerLevel level, PipeNetwork network, Job job, ItemResource item,
                             int want) {
        int total = 0;
        for (Owed owed : job.owes) {
            if (!owed.item.equals(item) || owed.remaining <= 0) {
                continue;
            }
            PipeNodeId dest = consumerBuffer(level, owed.consumer, item);
            if (dest != null) {
                total += throttles(level, owed.consumer)
                        ? acceptableAt(level, dest, item, owed.remaining)
                        : owed.remaining;
            }
        }
        if (item.equals(job.output) && job.remainingForRequester > 0) {
            total += job.remainingForRequester;
        }
        // Only output beyond every outstanding claim is surplus. Falling back to a default
        // route whenever the claimed destination was momentarily full is what let a producer
        // drain itself into a chest - or onto the floor - while still owing the item.
        int unclaimed = CraftJobPolicy.spareOf(want, outstandingClaims(job, item));
        if (unclaimed > 0) {
            total += SinkFinder.nearest(level, network, job.crafter.pos(), item, unclaimed)
                    .map(SinkFinder.Sink::accept)
                    .orElse(0);
        }
        return total;
    }

    /**
     * Sends freshly crafted output straight to where it is owed.
     *
     * <p>Never puts the output back into the crafter's inventory first. That round trip is
     * what dropped intermediates on the floor: if the pattern table had no room the
     * re-insert spilled, and even when it fitted the send queue then had to re-extract and
     * could race the table's next craft. Items are held here and dispatched once, which is
     * how Logistics Pipes does it.
     */
    private void shipOutput(ServerLevel level,
                            PipeNetwork network,
                            Job job,
                            ItemResource item,
                            int count,
                            Direction from,
                            Map<String, BlockPos> satellites) {
        int left = count;

        // 1) Settle what the plan says this job owes downstream. Bound at plan time, so it
        // does not depend on the consumer happening to look hungry on this tick.
        //
        // Iterated over a copy because a successful send rotates the list. Logistics Pipes
        // does the same in its order manager: after shipping to a destination it moves every
        // order for that destination to the back, so the next dispatch goes somewhere else.
        // Serving strictly head-first instead means the first consumer absorbs everything
        // until it is full - with five alloy smelters sharing a trickle of copper, two filled
        // up and the other three sat waiting with only their redstone.
        PipeNodeId served = null;
        for (Owed owed : List.copyOf(job.owes)) {
            if (left <= 0) {
                break;
            }
            if (!owed.item.equals(item) || owed.remaining <= 0) {
                continue;
            }
            PipeNodeId dest = consumerBuffer(level, owed.consumer, item);
            if (dest == null) {
                continue;
            }
            // Only as much as the consumer can actually hold right now, in-flight parcels
            // included. What will not fit stays owed and is retried next tick - the claim is
            // paced, never abandoned.
            int send = Math.min(left, owed.remaining);
            if (throttles(level, owed.consumer)) {
                send = Math.min(send, acceptableAt(level, dest, item, send));
            }
            if (send > 0 && dispatchHeld(level, job.crafter, dest, item, send, from)) {
                owed.remaining -= send;
                left -= send;
                served = owed.consumer;
            }
        }
        if (served != null) {
            job.rotatePast(served);
        }

        // There is deliberately no "feed whoever else looks hungry" step here. It used to sit
        // between the plan's claims and the requester, and it was blind to what other
        // producers had already committed: feedTarget measured a consumer's need from its
        // buffer and inbound parcels, while outstanding Owed claims from other crafters are
        // not counted as inbound. So one producer would ship into another's allocation. The
        // planner splits a multi-ingredient craft as matched sets - copper and redstone to
        // the same machine - and crossing those allocations left two alloy smelters holding
        // copper with no job and three holding redstone waiting for copper that was already
        // spent elsewhere. Everything the plan intends is expressed as an Owed above or a
        // requester claim below; anything left after both is genuine surplus.

        // 2) Deliver what the original request still owes for this output.
        if (left > 0 && item.equals(job.output) && job.remainingForRequester > 0) {
            CraftJobPolicy.SurplusSplit split =
                    CraftJobPolicy.splitSurplus(left, job.remainingForRequester);
            // Never capped: the requester is an address, not a machine. Capping it is what
            // left a finished chest sitting in its pattern table.
            int toRequester = split.toRequester();
            if (toRequester > 0
                    && dispatchHeld(level, job.crafter, job.requester, item, toRequester, from)) {
                job.remainingForRequester -= toRequester;
                left -= toRequester;
            }
        }

        // 3) Genuine surplus only - output this job no longer owes anyone. Anything still
        // claimed stays claimed even when its consumer is full this tick.
        int surplus = CraftJobPolicy.spareOf(left, outstandingClaims(job, item));
        if (surplus > 0) {
            int unplaced = routeHeldSurplus(level, network, job.crafter.pos(), item, surplus, from);
            left -= surplus - unplaced;
        }
        if (left <= 0) {
            return;
        }
        // 4) Owed but nowhere to put it yet. Held on the job and retried every tick, never
        // dropped and never pushed back into the machine: the machine stalling is the
        // backpressure, and it resumes on its own as the consumer drains. A job that ends
        // while still holding releases through routeOrDrop in releaseHeld.
        job.held.merge(item, left, Integer::sum);
    }

    /**
     * Retries anything a previous tick could not place.
     *
     * <p>Runs before the phase work so held output gets first claim on whatever room has
     * appeared downstream, ahead of a fresh extract competing for the same space.
     */
    private void flushHeld(ServerLevel level,
                           PipeNetwork network,
                           Job job,
                           Map<String, BlockPos> satellites) {
        if (job.held.isEmpty()) {
            return;
        }
        Map<ItemResource, Integer> pending = new LinkedHashMap<>(job.held);
        job.held.clear();
        for (Map.Entry<ItemResource, Integer> entry : pending.entrySet()) {
            shipOutput(level, network, job, entry.getKey(), entry.getValue(), null, satellites);
        }
    }

    /**
     * Routes or spills whatever a job was still holding when it ends.
     *
     * <p>A last resort for a job that is genuinely over - cancelled, failed, or finished with
     * nothing left owed. It must never be reached while a consumer is still waiting on that
     * output: routing an owed item to a default route strands whoever was expecting it. The
     * run-complete path keeps a job alive while it holds anything for exactly that reason.
     */
    private void releaseHeld(ServerLevel level, PipeNetwork network, Job job) {
        if (job.held.isEmpty()) {
            return;
        }
        for (Map.Entry<ItemResource, Integer> entry : job.held.entrySet()) {
            int left = routeHeldSurplus(
                    level, network, job.crafter.pos(), entry.getKey(), entry.getValue(), null);
            if (left > 0) {
                InventoryAccess.insertOrDrop(level, job.crafter.pos(), entry.getKey(), left);
            }
        }
        job.held.clear();
    }

    /**
     * Routes held items to the nearest sink with space: a passive supplier still short of
     * this item, else a default route.
     *
     * @return how many items are still held
     */
    private static int routeHeldSurplus(ServerLevel level,
                                        PipeNetwork network,
                                        BlockPos from,
                                        ItemResource item,
                                        int count,
                                        Direction side) {
        int left = count;
        while (left > 0) {
            Optional<SinkFinder.Sink> route =
                    SinkFinder.nearest(level, network, from, item, left);
            if (route.isEmpty()) {
                break;
            }
            SinkFinder.Sink sink = route.get();
            if (sink.pos().equals(from)) {
                int placed = InventoryAccess.insert(level, from, item, sink.accept());
                left -= placed;
                if (placed <= 0) {
                    break;
                }
                continue;
            }
            if (!dispatchHeld(level, PipeNodeId.of(level, from), PipeNodeId.of(level, sink.pos()),
                    item, sink.accept(), side)) {
                break;
            }
            left -= sink.accept();
        }
        return left;
    }

    /**
     * Dispatches items the caller is already holding, without extracting from anywhere.
     *
     * @return false when no route exists, leaving the caller holding the items
     */
    private static boolean dispatchHeld(ServerLevel any,
                                        PipeNodeId fromNode,
                                        PipeNodeId dest,
                                        ItemResource item,
                                        int count,
                                        Direction entrySide) {
        if (count <= 0) {
            return false;
        }
        ServerLevel fromLevel = levelOf(any, fromNode);
        if (fromLevel == null) {
            return false;
        }
        BlockPos from = fromNode.pos();
        if (fromNode.equals(dest)) {
            InventoryAccess.insertOrDrop(fromLevel, from, item, count);
            return true;
        }
        PipeNetwork fromNetwork = PipeNetwork.get(fromLevel);
        long promiseId = fromNetwork.ledger().promise(from, dest.pos(), item, count,
                fromLevel.getGameTime() + RequestService.PROMISE_TIMEOUT_TICKS);
        ItemShipment shipment = new ItemShipment(item, count, promiseId, entrySide);
        if (fromNetwork.injectItemToward(shipment, from, dest).isPresent()) {
            return true;
        }
        fromNetwork.ledger().cancel(promiseId);
        return false;
    }

    /**
     * The buffer a consuming crafter wants {@code item} delivered to, satellite aware.
     *
     * @return null when the consumer is gone or the satellite cannot be resolved
     */
    private static PipeNodeId consumerBuffer(ServerLevel any,
                                             PipeNodeId consumer,
                                             ItemResource item) {
        ServerLevel consumerLevel = levelOf(any, consumer);
        PipeNetwork consumerNetwork = networkOf(any, consumer);
        if (consumerLevel == null || consumerNetwork == null
                || !(consumerLevel.getBlockEntity(consumer.pos()) instanceof CraftingPipeBlockEntity be)
                || be.pattern().isEmpty()) {
            return null;
        }
        Map<String, BlockPos> satellites =
                SatelliteLookup.findAll(consumerLevel, consumerNetwork, consumer.pos());
        for (CraftPattern.CountedIngredient ingredient : be.pattern().ingredients()) {
            if (!ingredient.item().equals(item)) {
                continue;
            }
            Optional<BlockPos> destPos =
                    CraftJobPolicy.resolveDest(consumer.pos(), ingredient.satellite(), satellites);
            return destPos
                    .map(pos -> PipeNodeId.of(consumer.dimension(), pos))
                    .orElse(null);
        }
        return null;
    }

    private void fail(ServerLevel level, PipeNetwork network, Job job) {
        ServerLevel heldLevel = levelOf(level, job.crafter);
        PipeNetwork heldNetwork = networkOf(level, job.crafter);
        if (heldLevel != null && heldNetwork != null) {
            releaseHeld(heldLevel, heldNetwork, job);
        }
        List<PipeNodeId> buffers = new ArrayList<>();
        buffers.add(job.crafter);

        // What this job is entitled to take back: its pattern's ingredients, capped at the
        // amount it actually ordered. Never the machine's own fuel or product.
        Set<ItemResource> ingredients = new java.util.LinkedHashSet<>();
        Map<ItemResource, Integer> allowance = new java.util.LinkedHashMap<>();
        ServerLevel crafterLevel = levelOf(level, job.crafter);
        PipeNetwork crafterNetwork = networkOf(level, job.crafter);
        if (crafterLevel != null && crafterNetwork != null
                && crafterLevel.getBlockEntity(job.crafter.pos()) instanceof CraftingPipeBlockEntity be
                && !be.pattern().isEmpty()) {
            for (CraftPattern.CountedIngredient ingredient : be.pattern().ingredients()) {
                ingredients.add(ingredient.item());
                allowance.merge(ingredient.item(),
                        ingredient.count() * Math.max(1, job.runsRemaining), Integer::sum);
            }
            if (be.pattern().hasSatellite()) {
                SatelliteLookup.find(
                                crafterLevel, crafterNetwork, job.crafter.pos(),
                                be.pattern().satellite())
                        .ifPresent(pos -> buffers.add(
                                PipeNodeId.of(job.crafter.dimension(), pos)));
            }
        }

        for (PipeNodeId buffer : buffers) {
            cancelToward(level, buffer);
            ServerLevel bufferLevel = levelOf(level, buffer);
            PipeNetwork bufferNetwork = networkOf(level, buffer);
            if (bufferLevel != null && bufferNetwork != null) {
                reclaimIngredients(bufferLevel, bufferNetwork, buffer.pos(), ingredients, allowance);
            }
        }
    }

    /** Cancel send-queue pulls and reclaim in-flight parcels headed to {@code dest}. */
    private void cancelToward(ServerLevel any, PipeNodeId dest) {
        for (PipeNetwork net : PipeNetwork.instances()) {
            net.sendQueue().cancelToward(dest);
            net.cancelParcelsToward(dest, (at, shipment) ->
                    reclaimHeld(net.level(), net, at, shipment));
        }
    }

    private void cancelToward(ServerLevel any, PipeNodeId dest, ItemResource item) {
        for (PipeNetwork net : PipeNetwork.instances()) {
            net.sendQueue().cancelToward(dest, item);
            net.cancelParcelsToward(dest, item, (at, shipment) ->
                    reclaimHeld(net.level(), net, at, shipment));
        }
    }

    /**
     * Reclaims a cancelled in-flight shipment toward the default route.
     *
     * <p>Items have already left the provider, so the only alternatives are routing them
     * or spilling - never silently delete.
     */
    private static void reclaimHeld(ServerLevel level,
                                    PipeNetwork network,
                                    BlockPos at,
                                    ItemShipment shipment) {
        network.ledger().cancel(shipment.promiseId());
        int left = routeHeldSurplus(level, network, at, shipment.resource(), shipment.count(), null);
        if (left > 0) {
            InventoryAccess.insertOrDrop(level, at, shipment.resource(), left);
        }
    }

    /**
     * Returns only the ingredients this job brought in, leaving the machine alone.
     *
     * <p>Previously this summarised the whole attached inventory and routed all of it
     * away, which emptied a furnace of its fuel and its finished product on any failure.
     * A crafting pipe drives a machine it does not own: the only things it may reclaim are
     * the ingredients it delivered, and the only thing it may take out is the output its
     * pattern declares.
     */
    private void reclaimIngredients(ServerLevel level,
                                    PipeNetwork network,
                                    BlockPos buffer,
                                    Set<ItemResource> ingredients,
                                    Map<ItemResource, Integer> allowance) {
        for (ItemResource ingredient : ingredients) {
            int cap = allowance.getOrDefault(ingredient, 0);
            if (cap <= 0) {
                continue;
            }
            int held = Math.min(cap, InventoryAccess.count(level, buffer, ingredient));
            if (held > 0) {
                routeOrDrop(level, network, buffer, ingredient, held);
            }
        }
    }

    private void routeOrDrop(ServerLevel level,
                             PipeNetwork network,
                             BlockPos pipe,
                             ItemResource item,
                             int count) {
        if (count <= 0 || item.isEmpty()) {
            return;
        }
        Optional<SinkFinder.Sink> route = SinkFinder.nearest(level, network, pipe, item, count);
        if (route.isPresent()) {
            SinkFinder.Sink sink = route.get();
            if (sink.pos().equals(pipe)) {
                // Already at the sink buffer - leave it stored.
                return;
            }
            Direction from = InventoryAccess.sideHolding(level, pipe, item).orElse(null);
            int taken = PipeExtract.extract(level, network, pipe, item, sink.accept());
            if (taken <= 0) {
                return;
            }
            if (dispatchHeld(level, PipeNodeId.of(level, pipe), PipeNodeId.of(level, sink.pos()),
                    item, taken, from)) {
                return;
            }
            // Route vanished mid-send; put back or spill.
            InventoryAccess.insertOrDrop(level, pipe, item, taken);
            return;
        }
        int taken = PipeExtract.extract(level, network, pipe, item, count);
        InventoryAccess.drop(level, pipe, item, taken);
    }

    private record BufferKey(PipeNodeId dest, ItemResource item) {
    }

    private static final class Job {
        private final long id;
        /** Shared by every craft step produced by one {@link #enqueue} call. */
        private final long requestId;
        private final PipeNodeId crafter;
        private final PipeNodeId requester;
        private final ItemResource output;
        private int runsRemaining;
        private int remainingForRequester;
        /**
         * Where each input comes from, decided at plan time rather than re-derived here.
         *
         * <p>Not final: {@link #mergeStep} extends it when the planner hands back a second
         * step for this same crafter.
         */
        private List<RequestPlan.Sourced<PipeNodeId, ItemResource>> inputs;
        /** Crafters whose output this job consumes; it cannot finish before they run. */
        private List<PipeNodeId> dependsOn;
        /** Stock still owed by each bound provider, so a provider is never over-pulled. */
        private final Map<StockBinding, Integer> stockRemaining = new java.util.LinkedHashMap<>();
        /** Output this job must hand to downstream crafters, from the plan bindings. */
        private final List<Owed> owes = new ArrayList<>();
        /** Initial hard budget in ticks (for monitor progress bars). */
        private CraftJobPolicy.Phase phase = CraftJobPolicy.Phase.GATHER;
        /** True after ingredients for the current run were observed in-buffer. */
        private boolean armedThisRun;
        /**
         * True once this job has ever ordered ingredients.
         *
         * <p>Deliberately not cleared between runs. It answers "is output in the table
         * plausibly mine", and a job that ordered logs for run one still owns the planks
         * produced for run two. Clearing it stranded the last batch: by then the binding
         * was spent, so nothing new could be ordered, the flag stayed false, and the
         * finished output could never be claimed.
         *
         * <p>Still false for a job that has never ordered anything, which is what keeps a
         * fresh job from taking output a player left in the table.
         */
        private boolean requestedThisRun;
        /**
         * Results still owed for the current extract cycle, or {@code null} when not mid-extract.
         * Populated once a batch of runs is buffered, then dripped at the shared extract rate.
         */
        private Map<ItemResource, Integer> outputLeft;
        /** How many runs {@link #outputLeft} represents, for the {@code runsRemaining} decrement. */
        private int outputLeftRuns;
        /**
         * Output extracted but not yet placed anywhere, retried each tick.
         *
         * <p>Only ever populated for a pattern-table crafter, which is drained regardless of
         * downstream room so it cannot jam. Everything else is throttled before extraction,
         * so it has nothing left over to hold.
         */
        private final Map<ItemResource, Integer> held = new LinkedHashMap<>();
        /**
         * Game time the current batch started accumulating, or -1 while no output is waiting.
         *
         * <p>Deliberately not reset when a further run finishes: the window measures how long
         * this batch has been held, not how long since the last one landed. Resetting on each
         * new run would let a crafter that produces steadily hold output forever.
         */
        private long outputPendingSince = -1L;

        private Job(long id,
                    long requestId,
                    PipeNodeId crafter,
                    PipeNodeId requester,
                    ItemResource output,
                    int runs,
                    int forRequester,
                    List<RequestPlan.Sourced<PipeNodeId, ItemResource>> inputs,
                    List<PipeNodeId> dependsOn) {
            this.id = id;
            this.requestId = requestId;
            this.crafter = crafter;
            this.requester = requester;
            this.output = output;
            this.runsRemaining = runs;
            this.remainingForRequester = forRequester;
            this.inputs = List.copyOf(inputs);
            this.dependsOn = List.copyOf(dependsOn);
            for (RequestPlan.Sourced<PipeNodeId, ItemResource> input : inputs) {
                if (input.origin() instanceof RequestPlan.Origin.Stock<PipeNodeId> stock) {
                    stockRemaining.merge(
                            new StockBinding(stock.provider(), input.item()),
                            input.amount(), Integer::sum);
                }
            }
        }

        /**
         * Folds a second plan step for this same crafter into the job already tracking it.
         *
         * <p>Runs and the requester share simply add. Inputs (and any stock they bind) and
         * dependsOn accumulate so gather, {@link #awaitsCraft}, and severed-link checks see
         * the union of both steps rather than only the first.
         */
        private void mergeStep(RequestPlan.CraftStep<PipeNodeId, ItemResource> step, int forRequester) {
            runsRemaining += step.runs();
            remainingForRequester += forRequester;
            List<RequestPlan.Sourced<PipeNodeId, ItemResource>> merged =
                    new ArrayList<>(inputs);
            merged.addAll(step.inputs());
            inputs = List.copyOf(merged);
            for (RequestPlan.Sourced<PipeNodeId, ItemResource> input : step.inputs()) {
                if (input.origin() instanceof RequestPlan.Origin.Stock<PipeNodeId> stock) {
                    stockRemaining.merge(
                            new StockBinding(stock.provider(), input.item()),
                            input.amount(), Integer::sum);
                }
            }
            if (!step.dependsOn().isEmpty()) {
                Set<PipeNodeId> deps = new LinkedHashSet<>(dependsOn);
                deps.addAll(step.dependsOn());
                dependsOn = List.copyOf(deps);
            }
        }

        /** How much planned stock of {@code item} is still unpulled. */
        private int stockLeft(ItemResource item) {
            int total = 0;
            for (Map.Entry<StockBinding, Integer> entry : stockRemaining.entrySet()) {
                if (entry.getKey().item().equals(item)) {
                    total += Math.max(0, entry.getValue());
                }
            }
            return total;
        }

        /**
         * True when {@code pipe} is a craft endpoint this job cannot survive without.
         *
         * <p>Transport pipes on the route are intentionally excluded - parcels reroute or
         * strand on their own if that break actually cuts them off.
         */
        private boolean endpointRemoved(PipeNodeId pipe) {
            if (crafter.equals(pipe) || requester.equals(pipe) || dependsOn.contains(pipe)) {
                return true;
            }
            for (Owed owed : owes) {
                if (owed.consumer.equals(pipe)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Moves every claim for {@code consumer} to the back of the queue.
         *
         * <p>Round robin across consumers, the way Logistics Pipes rotates its order queue
         * after a successful send. Without it a producer serves its claims strictly in list
         * order and starves everyone behind the first consumer that is not yet satisfied.
         * Settled claims are dropped on the way past rather than kept as dead entries.
         */
        private void rotatePast(PipeNodeId consumer) {
            List<Owed> moved = new ArrayList<>();
            Iterator<Owed> iterator = owes.iterator();
            while (iterator.hasNext()) {
                Owed owed = iterator.next();
                if (owed.remaining <= 0) {
                    iterator.remove();
                } else if (owed.consumer.equals(consumer)) {
                    iterator.remove();
                    moved.add(owed);
                }
            }
            owes.addAll(moved);
        }

        /** True while any downstream crafter or the requester is still owed output. */
        private boolean owesAnything() {
            if (remainingForRequester > 0) {
                return true;
            }
            for (Owed owed : owes) {
                if (owed.remaining > 0) {
                    return true;
                }
            }
            return false;
        }

        /** True when {@code item} is produced by an upstream craft rather than pulled. */
        private boolean awaitsCraft(ItemResource item) {
            for (RequestPlan.Sourced<PipeNodeId, ItemResource> input : inputs) {
                if (input.item().equals(item) && input.fromCraft()) {
                    return true;
                }
            }
            return false;
        }
    }

    private record StockBinding(PipeNodeId provider, ItemResource item) {
    }

    /** A downstream crafter's claim on this job's output. */
    private static final class Owed {
        private final PipeNodeId consumer;
        private final ItemResource item;
        private int remaining;

        private Owed(PipeNodeId consumer, ItemResource item, int remaining) {
            this.consumer = consumer;
            this.item = item;
            this.remaining = remaining;
        }
    }
}
