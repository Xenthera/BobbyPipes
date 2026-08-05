package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.network.craft.CraftJobPolicy;
import com.bobby.bobbypipes.network.craft.CraftJobPolicy.GatherDecision;
import com.bobby.bobbypipes.network.craft.CraftJobPolicy.Phase;
import com.bobby.bobbypipes.network.craft.CraftJobPolicy.SurplusSplit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftJobPolicyTest {

    @Test
    @DisplayName("two slots of the same item (shulker shells) merge to need 2")
    void mergesDuplicateSlotIngredients() {
        // Shaped recipe lists each matrix cell separately: shell@1 + shell@1.
        Map<String, Integer> merged = CraftJobPolicy.mergeSlotNeeds(
                List.of("shell", "shell", "chest"),
                List.of(1, 1, 1));
        assertEquals(2, merged.get("shell"));
        assertEquals(1, merged.get("chest"));
        assertEquals(2, CraftJobPolicy.pullNeed(merged.get("shell"), 0, 0));
        assertEquals(1, CraftJobPolicy.pullNeed(merged.get("shell"), 0, 1),
                "one inbound still leaves one missing");
        assertEquals(0, CraftJobPolicy.pullNeed(merged.get("shell"), 0, 2));
        assertEquals(0, CraftJobPolicy.pullNeed(merged.get("shell"), 2, 0));
    }

    @Test
    @DisplayName("log->planks drain regression: pull 1, then 0 while inbound/in-flight")
    void drainRegressionPullNeed() {
        int perRun = 1; // one log per craft
        assertEquals(1, CraftJobPolicy.pullNeed(perRun, 0, 0), "first gather pulls one log");
        assertEquals(0, CraftJobPolicy.pullNeed(perRun, 0, CraftJobPolicy.inbound(1, 0)),
                "send-queue counts as inbound");
        assertEquals(0, CraftJobPolicy.pullNeed(perRun, 0, CraftJobPolicy.inbound(0, 1)),
                "in-flight parcel counts as inbound");
        assertEquals(0, CraftJobPolicy.pullNeed(perRun, 1, 0), "buffer already has the log");
    }

    @Test
    @DisplayName("craft-owed counts as inbound (supplier must not re-queue every scan)")
    void craftOwedCountsAsInbound() {
        assertEquals(3, CraftJobPolicy.inbound(0, 0, 3));
        assertEquals(0, CraftJobPolicy.pullNeed(3, 0, CraftJobPolicy.inbound(0, 0, 3)),
                "a craft job already owed to the requester covers the shortfall");
        assertEquals(5, CraftJobPolicy.inbound(1, 1, 3));
    }

    @Test
    @DisplayName("gather batch fills as many torch runs as the buffer can hold")
    void gatherBatchRunsFitsCapacity() {
        // stick + dust, 1 each per run; room for 5 of each; 100 runs left; cap 32
        assertEquals(5, CraftJobPolicy.gatherBatchRuns(
                100,
                new int[]{1, 1},
                new int[]{0, 0},
                new int[]{5, 5},
                32));
        assertEquals(32, CraftJobPolicy.gatherBatchRuns(
                100,
                new int[]{1, 1},
                new int[]{0, 0},
                new int[]{64, 64},
                32),
                "hard cap prevents flooding on huge requests");
        assertEquals(3, CraftJobPolicy.gatherBatchRuns(
                3,
                new int[]{1, 1},
                new int[]{0, 0},
                new int[]{64, 64},
                32),
                "never exceed runs remaining");
        assertEquals(2, CraftJobPolicy.gatherBatchRuns(
                10,
                new int[]{1, 1},
                new int[]{0, 0},
                new int[]{5, 2},
                32),
                "tightest ingredient wins");
        assertEquals(4, CraftJobPolicy.gatherBatchRuns(
                10,
                new int[]{1},
                new int[]{2},
                new int[]{2},
                32),
                "have + insertable both count toward capacity");
        assertEquals(0, CraftJobPolicy.gatherBatchRuns(
                10,
                new int[]{1},
                new int[]{0},
                new int[]{0},
                32),
                "no space means do not order");
        assertEquals(5, CraftJobPolicy.pullNeed(5, 0, 0),
                "scaled target pulls a full batch at once");
        assertEquals(2, CraftJobPolicy.pullNeed(5, 0, 3),
                "inbound still reduces the batch order");
    }

    @Test
    @DisplayName("extract batch waits for output slot cap, but never past what runs remain")
    void extractBatchRunsWaitsForCapOrFlushesEarly() {
        assertEquals(0, CraftJobPolicy.extractBatchRuns(0, 100, 64, true),
                "nothing ready yet");
        assertEquals(0, CraftJobPolicy.extractBatchRuns(5, 100, 64, true),
                "keep accumulating while more is still coming and cap not reached");
        assertEquals(64, CraftJobPolicy.extractBatchRuns(64, 100, 64, true),
                "flush once the output slot's cap is reached");
        assertEquals(20, CraftJobPolicy.extractBatchRuns(20, 20, 64, true),
                "flush once every remaining run has been produced, even under cap");
        assertEquals(5, CraftJobPolicy.extractBatchRuns(5, 100, 64, false),
                "input buffer ran dry before the cap: flush what is ready instead of stalling");
        assertEquals(0, CraftJobPolicy.extractBatchRuns(5, 0, 64, true),
                "nothing left on the job");
        assertEquals(1, CraftJobPolicy.extractBatchRuns(1, 100, 1, true),
                "non-stackable result caps at one run, same as today's one-at-a-time behaviour");
    }

    @Test
    @DisplayName("after buffer consume + armed + output ready, gather does not re-pull")
    void armedOutputReadyDoesNotPull() {
        GatherDecision decision = CraftJobPolicy.onGatherTick(true, false, true, false);
        assertEquals(Phase.WAIT_OUTPUT, decision.phase());
        assertTrue(decision.armedThisRun());
        assertFalse(decision.shouldPull(), "must not order another log");
    }

    @Test
    @DisplayName("requested pulls + instant craft (before gather observed) does not re-pull")
    void requestedThisRunPlusOutputReady() {
        GatherDecision decision = CraftJobPolicy.onGatherTick(false, false, true, true);
        assertEquals(Phase.WAIT_OUTPUT, decision.phase());
        assertFalse(decision.shouldPull());
    }

    @Test
    @DisplayName("leftover output without arm/request does not skip gather")
    void noFalseWaitOutputFromLeftover() {
        GatherDecision decision = CraftJobPolicy.onGatherTick(false, false, true, false);
        assertEquals(Phase.GATHER, decision.phase());
        assertFalse(decision.armedThisRun());
        assertTrue(decision.shouldPull(), "foreign/leftover output must not arm the run");
    }

    @Test
    @DisplayName("gatherComplete arms and enters WAIT_OUTPUT even if output already present")
    void gatherCompleteArmsAndWaits() {
        GatherDecision withOutput = CraftJobPolicy.onGatherTick(false, true, true, false);
        assertEquals(Phase.WAIT_OUTPUT, withOutput.phase());
        assertTrue(withOutput.armedThisRun());
        assertFalse(withOutput.shouldPull());

        GatherDecision withoutOutput = CraftJobPolicy.onGatherTick(false, true, false, false);
        assertEquals(Phase.WAIT_OUTPUT, withoutOutput.phase());
        assertTrue(withoutOutput.armedThisRun());
        assertFalse(withoutOutput.shouldPull());
    }

    @Test
    @DisplayName("multi-run remaining inputs use runsRemaining, not lifetime total")
    void multiRunRemainingInputs() {
        int perRun = 2; // e.g. 2 planks per stick craft
        assertEquals(6, CraftJobPolicy.remainingInputNeed(perRun, 3));
        assertEquals(4, CraftJobPolicy.missingInput(perRun, 2, 0, 0),
                "after 1 of 3 runs, 2 runs remain");
        assertEquals(1, CraftJobPolicy.missingInput(perRun, 2, 2, 1),
                "have+inbound reduce remaining need");
        assertEquals(0, CraftJobPolicy.missingInput(perRun, 0, 0, 0));
    }

    @Test
    @DisplayName("surplus split: request 5 from batch of 8")
    void surplusSplit() {
        SurplusSplit split = CraftJobPolicy.splitSurplus(8, 5);
        assertEquals(5, split.toRequester());
        assertEquals(3, split.surplus());

        SurplusSplit exact = CraftJobPolicy.splitSurplus(4, 4);
        assertEquals(4, exact.toRequester());
        assertEquals(0, exact.surplus());

        SurplusSplit overOwed = CraftJobPolicy.splitSurplus(4, 10);
        assertEquals(4, overOwed.toRequester());
        assertEquals(0, overOwed.surplus());
    }

    @Test
    @DisplayName("satellite dest resolves; missing name fails closed")
    void satelliteDest() {
        Map<String, String> sats = Map.of("furnace", "sat-node");
        assertEquals(Optional.of("crafter"), CraftJobPolicy.resolveDest("crafter", "", sats));
        assertEquals(Optional.of("crafter"), CraftJobPolicy.resolveDest("crafter", null, sats));
        assertEquals(Optional.of("sat-node"), CraftJobPolicy.resolveDest("crafter", "furnace", sats));
        assertTrue(CraftJobPolicy.resolveDest("crafter", "missing", sats).isEmpty(),
                "missing satellite must not silently fall back");
    }

    @Test
    @DisplayName("only the oldest job index is active for a crafter")
    void activeCrafterJob() {
        assertTrue(CraftJobPolicy.isActiveCrafterJob(0, 0));
        assertFalse(CraftJobPolicy.isActiveCrafterJob(2, 0));
    }

    @Test
    @DisplayName("afterRunComplete returns GATHER while runs remain")
    void afterRunComplete() {
        assertEquals(Phase.GATHER, CraftJobPolicy.afterRunComplete(2, false).phase());
        assertFalse(CraftJobPolicy.afterRunComplete(2, false).armedThisRun());
        assertEquals(Phase.WAIT_OUTPUT, CraftJobPolicy.afterRunComplete(0, false).phase());
    }

    @Test
    @DisplayName("batch leftover output (1 log->4 planks) does not re-gather next run")
    void afterRunCompleteUsesLeftoverOutput() {
        var next = CraftJobPolicy.afterRunComplete(3, true);
        assertEquals(Phase.WAIT_OUTPUT, next.phase());
        assertTrue(next.armedThisRun(), "armed so gather will not pull another log");
        assertFalse(CraftJobPolicy.onGatherTick(next.armedThisRun(), false, true, false).shouldPull());
    }
}
