package com.bobby.bobbypipes.logistics;

import com.bobby.bobbypipes.logistics.craft.CraftJobPolicy;
import com.bobby.bobbypipes.logistics.craft.CraftJobPolicy.GatherDecision;
import com.bobby.bobbypipes.logistics.craft.CraftJobPolicy.Phase;
import com.bobby.bobbypipes.logistics.craft.CraftJobPolicy.SurplusSplit;
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
        assertEquals(0, CraftJobPolicy.extractBatchRuns(0, 100, 64, true, 0, 40),
                "nothing ready yet");
        assertEquals(0, CraftJobPolicy.extractBatchRuns(5, 100, 64, true, 0, 40),
                "keep accumulating while more is still coming and cap not reached");
        assertEquals(64, CraftJobPolicy.extractBatchRuns(64, 100, 64, true, 0, 40),
                "flush once the output slot's cap is reached");
        assertEquals(20, CraftJobPolicy.extractBatchRuns(20, 20, 64, true, 0, 40),
                "flush once every remaining run has been produced, even under cap");
        assertEquals(5, CraftJobPolicy.extractBatchRuns(5, 100, 64, false, 0, 40),
                "input buffer ran dry before the cap: flush what is ready instead of stalling");
        assertEquals(0, CraftJobPolicy.extractBatchRuns(5, 0, 64, true, 0, 40),
                "nothing left on the job");
        assertEquals(1, CraftJobPolicy.extractBatchRuns(1, 100, 1, true, 0, 40),
                "non-stackable result caps at one run, same as today's one-at-a-time behaviour");
    }

    @Test
    @DisplayName("a job holding output for a consumer is not closed just because its runs ran out")
    void heldOutputKeepsAJobOpen() {
        // The 10-alloy-block stall: the third copper producer finished its thirty runs while
        // one ingot was still held for a momentarily full alloy smelter. Closing there posted
        // that ingot to a default route and the alloy craft waited on it forever.
        assertFalse(CraftJobPolicy.mayCloseJob(0, true), "runs done but still holding");
        assertTrue(CraftJobPolicy.mayCloseJob(0, false), "runs done and nothing held: close it");
        assertFalse(CraftJobPolicy.mayCloseJob(3, false), "runs left: not finished either way");
        assertFalse(CraftJobPolicy.mayCloseJob(3, true));
    }

    @Test
    @DisplayName("output that is still owed is never spare, however full the consumer is")
    void owedOutputIsNeverSpare() {
        // Three copper smelters feeding one alloy smelter. The alloy smelter is full, so the
        // room check says nothing fits - but the ingots are still owed to it, so none of them
        // are spare and none may be routed away or dropped. They wait.
        assertEquals(0, CraftJobPolicy.spareOf(8, 8), "every ingot is claimed");
        assertEquals(0, CraftJobPolicy.spareOf(8, 64), "more claimed than produced is still none spare");

        // A batch recipe really can overshoot: one log makes four planks when only one is owed.
        assertEquals(3, CraftJobPolicy.spareOf(4, 1));
        assertEquals(4, CraftJobPolicy.spareOf(4, 0), "nothing owed, all of it spare");
    }

    @Test
    @DisplayName("a crafter's own promise does not count against the room it is shipping into")
    void roomForIgnoresItsOwnPromise() {
        // The regression: a wood -> planks -> chest order finished the chest and then never
        // delivered it. The delivery clamp counted craft-owed output as inbound, so the job
        // subtracted its own outstanding claim from the destination's free space, decided
        // nothing fit, refused to extract, and left the chest sitting in the pattern table.
        assertEquals(1, CraftJobPolicy.roomFor(1, 1, 0),
                "one slot free and nothing actually travelling: the last item fits");

        // Only things really on their way reduce the room.
        assertEquals(0, CraftJobPolicy.roomFor(1, 1, 1), "a parcel already flying takes the slot");
        assertEquals(4, CraftJobPolicy.roomFor(8, 6, 2), "space minus what is en route");
        assertEquals(8, CraftJobPolicy.roomFor(8, 64, 0), "never more than asked for");
        assertEquals(0, CraftJobPolicy.roomFor(0, 64, 0), "nothing wanted, nothing offered");
        assertEquals(0, CraftJobPolicy.roomFor(4, 0, 0), "a full destination takes nothing");
        assertEquals(0, CraftJobPolicy.roomFor(4, 2, 9), "more en route than space is not negative");

        // The under-report: an alloy smelter holding four ingots has ~60 free. Probed for the
        // ask plus what is flying, all eight owed ingots fit.
        assertEquals(8, CraftJobPolicy.roomFor(8, 60, 4), "a nearly empty machine takes the lot");
        // Probing only the ask would have measured space as 8 and answered 4 - and 0 once
        // eight were travelling, which is what stalled three smelters onto one.
        assertEquals(4, CraftJobPolicy.roomFor(8, 8, 4), "what the capped probe used to report");
    }

    @Test
    @DisplayName("a machine that is still fed ships what it has once the batch window expires")
    void extractBatchRunsFlushesOnDeadline() {
        // A furnace smelting one item at a time: gather keeps its input topped up, so
        // moreComing never goes false. Without the deadline this waited for the whole order.
        assertEquals(0, CraftJobPolicy.extractBatchRuns(1, 5, 64, true, 39, 40),
                "still inside the batch window");
        assertEquals(1, CraftJobPolicy.extractBatchRuns(1, 5, 64, true, 40, 40),
                "window expired: ship the one ingot rather than hold it for the other four");
        assertEquals(3, CraftJobPolicy.extractBatchRuns(3, 5, 64, true, 100, 40),
                "ships every complete run that is ready, not just one");

        // The deadlock: an order larger than the output slot can hold could never reach
        // target, and with the input kept fed it had no other way out.
        assertEquals(64, CraftJobPolicy.extractBatchRuns(64, 66, 64, true, 0, 40),
                "an order above the slot cap still flushes at the cap");
        assertEquals(12, CraftJobPolicy.extractBatchRuns(12, 66, 64, true, 40, 40),
                "and no longer waits for a batch the machine cannot physically hold");

        assertEquals(0, CraftJobPolicy.extractBatchRuns(0, 66, 64, true, 999, 40),
                "an expired window still ships nothing when nothing is ready");
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
