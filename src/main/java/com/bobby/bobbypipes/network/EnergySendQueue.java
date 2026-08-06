package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.request.DeliveryLedger;
import com.bobby.bobbypipes.transit.EnergyShipment;
import com.bobby.bobbypipes.transit.ParcelTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Drains accepted energy withdrawals through providers at their send rate.
 *
 * <p>Mirrors {@link ProviderSendQueue} exactly, minus the item identity: a job is just a
 * source, a destination, and an amount. Uses its own {@link ExtractPulseBudget} (energy
 * amounts are a different scale than item counts, one pulse is one packet's worth), not
 * the item one, so an energy provider and an item provider never compete for the same
 * budget even if that never actually happens in practice (they are different block types).
 */
public final class EnergySendQueue {

    /** FE one provider may extract per send pulse, one packet's worth. */
    public static final int FE_PER_PULSE = EnergyRequestService.PACKET_SIZE_FE;

    /** Ticks between extract pulses, same cadence as item providers. */
    public static final int PULSE_INTERVAL_TICKS = PipeExtractRates.PULSE_INTERVAL_TICKS;

    private final List<Job> jobs = new ArrayList<>();
    private final ExtractPulseBudget<BlockPos> budget;

    public EnergySendQueue(ExtractPulseBudget<BlockPos> budget) {
        this.budget = budget;
    }

    public static ExtractPulseBudget<BlockPos> defaultBudget() {
        return new ExtractPulseBudget<>(FE_PER_PULSE, PULSE_INTERVAL_TICKS);
    }

    /**
     * Queues {@code amountFe} to pull from {@code source} toward {@code dest}.
     *
     * <p>Nothing is extracted yet, that happens on {@link #tick} as the provider's send
     * budget allows.
     */
    public void enqueue(BlockPos source, BlockPos dest, int amountFe) {
        enqueue(source, dest, amountFe, Set.of());
    }

    /**
     * As {@link #enqueue(BlockPos, BlockPos, int)}, but {@code excluded} storages are off
     * limits when the pull actually happens.
     *
     * <p>The set has to ride along on the job rather than only being applied when the job
     * was created: extraction happens ticks later, and without it the queue would happily
     * drain the very storage the requester was trying to fill.
     */
    public void enqueue(BlockPos source, BlockPos dest, int amountFe, Set<BlockPos> excluded) {
        if (amountFe <= 0) {
            return;
        }
        jobs.add(new Job(source.immutable(), dest.immutable(), amountFe, Set.copyOf(excluded)));
    }

    /** How much FE is still waiting to leave {@code source}. */
    public int queued(BlockPos source) {
        int total = 0;
        for (Job job : jobs) {
            if (job.source.equals(source)) {
                total += job.remaining;
            }
        }
        return total;
    }

    /** How much FE is already scheduled to arrive at {@code dest}. */
    public int queuedTo(BlockPos dest) {
        int total = 0;
        for (Job job : jobs) {
            if (job.dest.equals(dest)) {
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
     * @return how much FE was cancelled (sum of remaining amounts)
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
     * Extracts and injects as many pulse-sized parcels as current provider budgets allow.
     *
     * @return how much FE left providers this tick
     */
    public int tick(ServerLevel level,
                    DeliveryLedger<BlockPos, EnergyKind> ledger,
                    ParcelTracker<BlockPos, EnergyShipment> parcels,
                    RoutingSnapshot<BlockPos> routes) {
        if (jobs.isEmpty()) {
            return 0;
        }

        long gameTime = level.getGameTime();
        int shipped = 0;
        // One dispatch per provider per tick, same reasoning as the item queue: draining
        // several jobs from the same provider in one tick would leave overlapping parcels
        // at the same point at the same instant.
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

            // Re-check the reserve at pull time, not just when the job was accepted: a
            // Supplier target can be raised, or the storage drained by something else,
            // between queuing and this pulse. Without it a job accepted a second ago would
            // still take the floor a Supplier is holding on that storage.
            int reserved = SupplierReserves.reservedFe(level, job.source);
            int want = Math.min(job.remaining, allow);
            if (reserved > 0) {
                int free = EnergyAccess.extractable(
                        level, job.source, saturatingAdd(want, reserved), job.excluded) - reserved;
                want = Math.min(want, Math.max(0, free));
            }
            if (want <= 0) {
                // Nothing left above the floor. Drop the rest of the job rather than let it
                // sit and count against the destination's inbound forever; the Supplier that
                // wanted it re-asks on its next scan anyway.
                iterator.remove();
                continue;
            }

            Direction from = EnergyAccess.sideHolding(level, job.source, job.excluded).orElse(null);
            int taken = EnergyAccess.extract(level, job.source, want, job.excluded);
            if (taken <= 0) {
                // Storage emptied or pipe broken - drop the rest of this job.
                iterator.remove();
                continue;
            }

            long promiseId = ledger.promise(job.source, job.dest, EnergyKind.ENERGY, taken,
                    gameTime + EnergyRequestService.PROMISE_TIMEOUT_TICKS);
            EnergyShipment shipment = new EnergyShipment(taken, promiseId, from);
            boolean injected = parcels.inject(shipment, job.source, job.dest, routes).isPresent();
            if (!injected) {
                ledger.cancel(promiseId);
                // Put it back where it came from, which must respect the same exclusion,
                // or a failed injection would hand the energy to the wrong storage.
                EnergyAccess.insert(level, job.source, taken, job.excluded);
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

    /** {@code a + b} capped at {@link Integer#MAX_VALUE} so a huge reserve never overflows. */
    private static int saturatingAdd(int a, int b) {
        long sum = (long) a + (long) b;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private static final class Job {
        private final BlockPos source;
        private final BlockPos dest;
        /** Storages this job must not touch, normally the destination's own. */
        private final Set<BlockPos> excluded;
        private int remaining;

        private Job(BlockPos source, BlockPos dest, int remaining, Set<BlockPos> excluded) {
            this.source = source;
            this.dest = dest;
            this.remaining = remaining;
            this.excluded = excluded;
        }
    }
}
