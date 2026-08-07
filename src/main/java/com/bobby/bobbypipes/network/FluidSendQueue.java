package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.request.DeliveryLedger;
import com.bobby.bobbypipes.transit.FluidShipment;
import com.bobby.bobbypipes.transit.ParcelTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Drains accepted fluid withdrawals through providers at their send rate.
 *
 * <p>Mirrors {@link ProviderSendQueue} exactly (fluid has a real resource identity, unlike
 * energy, so this is structurally the item queue with {@code FluidResource} in place of
 * {@code ItemResource}), using its own {@link ExtractPulseBudget} at a packet-sized amount
 * per pulse rather than the item one.
 */
public final class FluidSendQueue {

    /**
     * mB one provider may extract per send pulse, one packet's worth.
     *
     * <p>Same reasoning as {@link EnergySendQueue#FE_PER_PULSE}: the tank's own transfer
     * rate is the real throttle, this only caps a single parcel.
     */
    public static final int MB_PER_PULSE = FluidRequestService.MAX_PACKET_MB;

    /** Ticks between extract pulses, same cadence as item and energy providers. */
    public static final int PULSE_INTERVAL_TICKS = PipeExtractRates.PULSE_INTERVAL_TICKS;

    private final List<Job> jobs = new ArrayList<>();
    private final ExtractPulseBudget<BlockPos> budget;

    public FluidSendQueue(ExtractPulseBudget<BlockPos> budget) {
        this.budget = budget;
    }

    public static ExtractPulseBudget<BlockPos> defaultBudget() {
        return new ExtractPulseBudget<>(MB_PER_PULSE, PULSE_INTERVAL_TICKS);
    }

    /**
     * Queues {@code amountMb} of {@code fluid} to pull from {@code source} toward
     * {@code dest}. Nothing is extracted yet, that happens on {@link #tick} as the
     * provider's send budget allows.
     */
    public void enqueue(BlockPos source, BlockPos dest, FluidResource fluid, int amountMb) {
        enqueue(source, dest, fluid, amountMb, Set.of());
    }

    /**
     * As {@link #enqueue(BlockPos, BlockPos, FluidResource, int)}, but {@code excluded}
     * tanks are off limits when the pull actually happens.
     *
     * <p>The set rides on the job because extraction happens ticks after queuing; applying
     * it only at request time would leave the queue draining the very tank the requester
     * was trying to fill.
     */
    public void enqueue(BlockPos source, BlockPos dest, FluidResource fluid, int amountMb,
                        Set<BlockPos> excluded) {
        if (fluid.isEmpty() || amountMb <= 0) {
            return;
        }
        jobs.add(new Job(source.immutable(), dest.immutable(), fluid, amountMb, Set.copyOf(excluded)));
    }

    /** How much of {@code fluid} is still waiting to leave {@code source}. */
    public int queued(BlockPos source, FluidResource fluid) {
        int total = 0;
        for (Job job : jobs) {
            if (job.source.equals(source) && job.fluid.equals(fluid)) {
                total += job.remaining;
            }
        }
        return total;
    }

    /** Total mB still waiting to leave {@code source}, across every queued job. */
    public int queuedFrom(BlockPos source) {
        int total = 0;
        for (Job job : jobs) {
            if (job.source.equals(source)) {
                total += job.remaining;
            }
        }
        return total;
    }

    /** How much of {@code fluid} is already scheduled to arrive at {@code dest}. */
    public int queuedTo(BlockPos dest, FluidResource fluid) {
        int total = 0;
        for (Job job : jobs) {
            if (job.dest.equals(dest) && job.fluid.equals(fluid)) {
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
     * @return how much mB was cancelled (sum of remaining amounts)
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
     * Extracts and injects one parcel per provider whose pulse has come round, carrying
     * whatever the source tank releases in a single extract call.
     *
     * @return how much mB left providers this tick
     */
    public int tick(ServerLevel level,
                    PipeNetwork network,
                    DeliveryLedger<BlockPos, FluidResource> ledger,
                    ParcelTracker<BlockPos, FluidShipment> parcels,
                    RoutingSnapshot<BlockPos> routes) {
        if (jobs.isEmpty()) {
            return 0;
        }

        long gameTime = level.getGameTime();
        int shipped = 0;
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

            // Same reserve re-check as the energy queue: a Supplier target on this tank is
            // a floor, and it may have moved between queuing and this pulse.
            int reserved = SupplierReserves.reservedMb(level, job.source, job.fluid);
            int want = Math.min(job.remaining, allow);
            if (reserved > 0) {
                int free = FluidAccess.extractable(
                        level, job.source, job.fluid, saturatingAdd(want, reserved), job.excluded)
 - reserved;
                want = Math.min(want, Math.max(0, free));
            }
            if (want <= 0) {
                // Nothing above the floor. Drop it rather than let it count against the
                // destination's inbound forever; the Supplier re-asks on its next scan.
                iterator.remove();
                continue;
            }

            Direction from =
                    FluidAccess.sideHolding(level, job.source, job.fluid, job.excluded).orElse(null);
            if (!network.power().canAfford(job.source,
                    com.bobby.bobbypipes.network.power.LogisticsPowerCosts.FLUID_PROVIDER)) {
                continue;
            }
            int taken = FluidAccess.extract(level, job.source, job.fluid, want, job.excluded);
            if (taken <= 0) {
                iterator.remove();
                continue;
            }
            if (!network.power().trySpend(job.source,
                    com.bobby.bobbypipes.network.power.PowerSpendKind.FLUID_PROVIDER)) {
                FluidAccess.insert(level, job.source, job.fluid, taken, job.excluded);
                continue;
            }

            long promiseId = ledger.promise(job.source, job.dest, job.fluid, taken,
                    gameTime + FluidRequestService.PROMISE_TIMEOUT_TICKS);
            FluidShipment shipment = new FluidShipment(job.fluid, taken, promiseId, from);
            boolean injected = network.injectFluidToward(
                    shipment, job.source, PipeNodeId.of(level, job.dest)).isPresent();
            if (!injected) {
                ledger.cancel(promiseId);
                // Refund respects the same exclusion, or a failed injection would hand the
                // fluid to the storage this job was told to leave alone.
                FluidAccess.insert(level, job.source, job.fluid, taken, job.excluded);
                iterator.remove();
                continue;
            }

            // Whole window, same reasoning as EnergySendQueue: one parcel per provider per
            // pulse regardless of size, or small pulls would dispatch every tick and stack
            // parcels on top of each other in the pipe.
            budget.consume(job.source, allow);
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
        private final FluidResource fluid;
        /** Tanks this job must not touch, normally the destination's own. */
        private final Set<BlockPos> excluded;
        private int remaining;

        private Job(BlockPos source, BlockPos dest, FluidResource fluid, int remaining,
                    Set<BlockPos> excluded) {
            this.source = source;
            this.dest = dest;
            this.fluid = fluid;
            this.remaining = remaining;
            this.excluded = excluded;
        }
    }
}
