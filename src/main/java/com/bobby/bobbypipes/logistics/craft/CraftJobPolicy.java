package com.bobby.bobbypipes.logistics.craft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure deliver-and-wait craft-job rules: inbound math, pull need, phase/arm transitions,
 * remaining-input need, and surplus split.
 *
 * <p>No world access - unit tests drive this with fake have/inbound/output counts.
 */
public final class CraftJobPolicy {

    public enum Phase {
        /** Request ingredients for the current run. */
        GATHER,
        /** Ingredients delivered (armed); wait for the machine/table to produce output. */
        WAIT_OUTPUT
    }

    private CraftJobPolicy() {
    }

    /** Send-queue remainder plus parcels already flying toward the buffer. */
    public static int inbound(int queuedTo, int inFlightTo) {
        return inbound(queuedTo, inFlightTo, 0);
    }

    /**
     * @param craftingTo output still owed by active craft jobs to this destination
     *                   (not yet extracted / parcelled)
     */
    public static int inbound(int queuedTo, int inFlightTo, int craftingTo) {
        return Math.max(0, queuedTo) + Math.max(0, inFlightTo) + Math.max(0, craftingTo);
    }

    /**
     * How many more of one ingredient to pull for the current gather target.
     *
     * @param targetNeed count required in the buffer for this gather (already scaled by
     *                   batch runs; for one run this is the merged per-slot total)
     * @param have       already in the buffer
     * @param inbound    {@link #inbound(int, int)} toward that buffer
     */
    public static int pullNeed(int targetNeed, int have, int inbound) {
        return Math.max(0, targetNeed - Math.max(0, have) - Math.max(0, inbound));
    }

    /**
     * How many runs worth of ingredients to aim for in buffers this gather.
     *
     * <p>Takes as many as will fit ({@code have + insertable}) without exceeding the job's
     * remaining runs or {@code maxBatch}. Does not count inbound toward capacity - callers
     * use {@link #pullNeed} so already-ordered parcels are not doubled.
     *
     * @param perRunNeeds merged per-run counts, one entry per buffer key
     * @param have        items already in each buffer
     * @param insertable  free insert capacity in each buffer right now
     * @return 0 when nothing can fit; otherwise at least 1 when any run fits
     */
    public static int gatherBatchRuns(int runsRemaining,
                                      int[] perRunNeeds,
                                      int[] have,
                                      int[] insertable,
                                      int maxBatch) {
        if (runsRemaining <= 0 || maxBatch <= 0
                || perRunNeeds == null || have == null || insertable == null
                || perRunNeeds.length != have.length
                || perRunNeeds.length != insertable.length) {
            return 0;
        }
        int runs = Math.min(runsRemaining, maxBatch);
        for (int i = 0; i < perRunNeeds.length; i++) {
            int perRun = perRunNeeds[i];
            if (perRun <= 0) {
                continue;
            }
            int maxItems = Math.max(0, have[i]) + Math.max(0, insertable[i]);
            runs = Math.min(runs, maxItems / perRun);
        }
        return Math.max(0, runs);
    }

    /**
     * How many complete runs' worth of output to extract right now.
     *
     * <p>Mirrors {@link #gatherBatchRuns} on the way out: rather than shipping every single
     * run the moment it is ready (one item at a time behind a fast auto-crafter), let output
     * pile up in the crafter's own output slot and take it in one batch. The batch never
     * exceeds what remains on the job or what the output slot can physically hold before the
     * crafter itself pauses ({@code outputCapRuns}).
     *
     * <p>{@code moreComing} is the escape hatch: if the input buffer cannot support another
     * run right now, nothing more is going to appear until the next gather, so waiting any
     * longer would only stall the job for no benefit - take what is ready instead.
     *
     * @param availableRuns runs' worth of result already sitting in the crafter
     * @param runsRemaining runs left on the job
     * @param outputCapRuns runs' worth that fit in the output slot before it maxes out
     * @param moreComing    true while the input buffer can still support another run
     *                      without another gather
     * @return 0 to keep accumulating; otherwise how many runs to extract now
     */
    public static int extractBatchRuns(int availableRuns,
                                       int runsRemaining,
                                       int outputCapRuns,
                                       boolean moreComing) {
        if (availableRuns <= 0 || runsRemaining <= 0) {
            return 0;
        }
        int target = Math.max(1, Math.min(runsRemaining, outputCapRuns));
        if (availableRuns >= target || !moreComing) {
            return Math.min(availableRuns, runsRemaining);
        }
        return 0;
    }

    /**
     * Merge per-slot ingredient counts that share a buffer key.
     *
     * <p>Shaped recipes list each matrix slot separately (two shells -> two entries of 1).
     * Gathering must sum those before comparing against have/inbound, or the second slot
     * will treat the first pull as already covering it.
     */
    public static <K> Map<K, Integer> mergeSlotNeeds(List<K> keys, List<Integer> counts) {
        if (keys.size() != counts.size()) {
            throw new IllegalArgumentException("keys/counts size mismatch");
        }
        Map<K, Integer> totals = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            int amount = Math.max(0, counts.get(i));
            if (amount == 0 || keys.get(i) == null) {
                continue;
            }
            totals.merge(keys.get(i), amount, Integer::sum);
        }
        return totals;
    }

    /** Total of one ingredient still owed across unfinished runs. */
    public static int remainingInputNeed(int perRun, int runsRemaining) {
        if (perRun <= 0 || runsRemaining <= 0) {
            return 0;
        }
        return perRun * runsRemaining;
    }

    /**
     * How many of an ingredient another job still wants fed into its buffer.
     *
     * <p>Uses remaining runs, not the lifetime plan total, so a half-finished multi-run
     * job is not over-fed.
     */
    public static int missingInput(int perRun, int runsRemaining, int have, int inbound) {
        return Math.max(0, remainingInputNeed(perRun, runsRemaining) - Math.max(0, have) - Math.max(0, inbound));
    }

    /**
     * Result of evaluating one GATHER tick.
     *
     * @param phase         phase after this evaluation
     * @param armedThisRun  true once ingredients for this run have been observed in-buffer
     * @param shouldPull    whether {@code ensureInputs} should run
     */
    public record GatherDecision(Phase phase, boolean armedThisRun, boolean shouldPull) {
    }

    /**
     * GATHER-tick rules.
     *
     * <ul>
     *   <li>Do not enter WAIT_OUTPUT from leftover/foreign output alone.</li>
     *   <li>Arm when {@code gatherComplete} (ingredients sitting in buffers).</li>
     *   <li>If this run already requested pulls ({@code requestedThisRun}) and output is
     *       ready, treat as armed - covers pattern-table crafting before we observe
     *       gather-complete.</li>
     * </ul>
     *
     * @param requestedThisRun true if this run already enqueued at least one ingredient pull
     */
    public static GatherDecision onGatherTick(boolean armedThisRun,
                                              boolean gatherComplete,
                                              boolean outputReady,
                                              boolean requestedThisRun) {
        if (gatherComplete) {
            return new GatherDecision(Phase.WAIT_OUTPUT, true, false);
        }
        if ((armedThisRun || requestedThisRun) && outputReady) {
            return new GatherDecision(Phase.WAIT_OUTPUT, true, false);
        }
        return new GatherDecision(Phase.GATHER, armedThisRun, true);
    }

    /**
     * Phase after one successful extract.
     *
     * <p>When more runs remain and output for the next run is already in the buffer
     * (1 log -> 4 planks while the pattern counts 1/run), stay in {@link Phase#WAIT_OUTPUT}
     * armed - do not gather another set of inputs. Otherwise leftover output alone at the
     * start of a job still must not skip gather ({@link #onGatherTick}).
     *
     * @param runsRemainingAfter runs left after decrementing the completed one
     * @param outputReadyForNext buffer already holds enough output for another extract
     */
    public record RunCompleteDecision(Phase phase, boolean armedThisRun) {
    }

    public static RunCompleteDecision afterRunComplete(int runsRemainingAfter,
                                                       boolean outputReadyForNext) {
        if (runsRemainingAfter <= 0) {
            return new RunCompleteDecision(Phase.WAIT_OUTPUT, false);
        }
        if (outputReadyForNext) {
            return new RunCompleteDecision(Phase.WAIT_OUTPUT, true);
        }
        return new RunCompleteDecision(Phase.GATHER, false);
    }

    /**
     * Split a craft batch between the requester and surplus (default route / drop).
     *
     * @param produced               items extracted this run
     * @param remainingForRequester  how many the original request still owes
     */
    public record SurplusSplit(int toRequester, int surplus) {
    }

    public static SurplusSplit splitSurplus(int produced, int remainingForRequester) {
        int owed = Math.max(0, remainingForRequester);
        int toRequester = Math.min(Math.max(0, produced), owed);
        return new SurplusSplit(toRequester, Math.max(0, produced) - toRequester);
    }

    /**
     * Resolve an ingredient buffer.
     *
     * @return crafter when satellite is blank; satellite pos when mapped; empty when the
     *         name is set but missing (caller should fail the job)
     */
    public static <N> Optional<N> resolveDest(N crafter,
                                              String satelliteName,
                                              Map<String, N> satellites) {
        if (satelliteName == null || satelliteName.isBlank()) {
            return Optional.of(crafter);
        }
        N sat = satellites.get(satelliteName);
        return Optional.ofNullable(sat);
    }

    /**
     * Whether this job is allowed to gather/extract when multiple jobs share a crafter.
     *
     * @param jobIndex      index of this job in the manager list
     * @param activeIndex   index of the oldest job for the same crafter, or {@code jobIndex}
     *                      when this job is that oldest
     */
    public static boolean isActiveCrafterJob(int jobIndex, int activeIndex) {
        return jobIndex == activeIndex;
    }
}
