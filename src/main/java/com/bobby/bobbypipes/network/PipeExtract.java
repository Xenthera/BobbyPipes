package com.bobby.bobbypipes.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Rate-limited extract from inventories touching a pipe.
 *
 * <p>Every pipe that pulls from the world shares {@link PipeNetwork#extractBudget()}: the
 * same pulse size and interval as basic providers.
 */
public final class PipeExtract {

    private PipeExtract() {
    }

    /**
     * Extracts up to {@code wanted} of {@code item}, clamped to {@code pipe}'s remaining
     * extract budget for this pulse.
     *
     * @return how many were actually removed
     */
    public static int extract(ServerLevel level,
                              PipeNetwork network,
                              BlockPos pipe,
                              ItemResource item,
                              int wanted) {
        if (item.isEmpty() || wanted <= 0) {
            return 0;
        }
        int allow = network.extractBudget().budget(pipe, level.getGameTime());
        int want = Math.min(wanted, allow);
        if (want <= 0) {
            return 0;
        }
        int taken = InventoryAccess.extract(level, pipe, item, want);
        if (taken > 0) {
            network.extractBudget().consume(pipe, taken);
        }
        return taken;
    }
}
