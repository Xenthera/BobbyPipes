package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.request.Demand;
import com.bobby.bobbypipes.request.RequestPlan;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestServiceSatisfiableTest {

    @Test
    @DisplayName("satisfiable amount sums direct withdrawals and craft outputs")
    void sumsWithdrawalsAndCraftOutputs() {
        // Use a fake ItemResource via empty plan structure - ItemResource needs MC.
        // This test only exercises arithmetic with null-safe equals on a stub identity.
        ItemResource item = ItemResource.EMPTY;
        PipeNodeId node = PipeNodeId.of(
                net.minecraft.world.level.Level.OVERWORLD, BlockPos.ZERO);
        RequestPlan<PipeNodeId, ItemResource> plan = new RequestPlan<>(
                List.of(new RequestPlan.Withdrawal<>(node, item, 5)),
                List.of(new RequestPlan.CraftStep<>(
                        node, item, 4, 2,
                        List.of(new RequestPlan.Sourced<>(
                                item, 2, new RequestPlan.Origin.Stock<>(node))))),
                List.of(new Demand<>(item, 3)));

        // EMPTY equals EMPTY; craft adds 8, withdrawal 5 → 13
        assertEquals(13, RequestService.satisfiableAmount(plan, item));
    }
}
