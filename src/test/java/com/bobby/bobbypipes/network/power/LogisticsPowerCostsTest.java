package com.bobby.bobbypipes.network.power;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogisticsPowerCostsTest {

    @Test
    void linkSameDimUsesManhattanCapped() {
        BlockPos a = new BlockPos(0, 0, 0);
        BlockPos b = new BlockPos(10, 0, 0);
        assertEquals(10, LogisticsPowerCosts.linkSameDimCost(a, b));

        BlockPos far = new BlockPos(500, 0, 0);
        assertEquals(LogisticsPowerCosts.LINK_SAME_DIM_CAP,
                LogisticsPowerCosts.linkSameDimCost(a, far));
    }

    @Test
    void samplerRollsAndPreservesKinds() {
        PowerUsageSampler sampler = new PowerUsageSampler(3);
        sampler.recordSpend(PowerSpendKind.PROVIDER, 5);
        sampler.recordSpend(PowerSpendKind.CRAFTING, 25);
        sampler.recordInput(40);
        sampler.flushSample();

        sampler.recordSpend(PowerSpendKind.REQUEST, 5);
        sampler.flushSample();

        assertEquals(2, sampler.size());
        PowerUsageSampler.Sample oldest = sampler.sampleAt(0);
        assertEquals(40, oldest.totalIn());
        assertEquals(30, oldest.totalOut());
        assertEquals(5, oldest.byKind()[PowerSpendKind.PROVIDER.ordinal()]);
        assertEquals(25, oldest.byKind()[PowerSpendKind.CRAFTING.ordinal()]);

        PowerUsageSampler.Sample newest = sampler.sampleAt(1);
        assertEquals(5, newest.totalOut());
        assertTrue(newest.byKind()[PowerSpendKind.REQUEST.ordinal()] == 5);
    }
}
