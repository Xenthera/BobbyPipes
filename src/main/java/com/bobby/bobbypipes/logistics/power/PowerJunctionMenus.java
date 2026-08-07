package com.bobby.bobbypipes.logistics.power;

import com.bobby.bobbypipes.block.entity.PowerJunctionBlockEntity;
import com.bobby.bobbypipes.menu.PowerJunctionMenu;
import com.bobby.bobbypipes.network.payload.PowerJunctionSyncPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Pushes power-junction GUI snapshots to players who have the menu open.
 */
public final class PowerJunctionMenus {

    private PowerJunctionMenus() {
    }

    public static void syncOpenMenus(ServerLevel level, ComponentPower power) {
        for (ServerPlayer player : level.players()) {
            AbstractContainerMenu menu = player.containerMenu;
            if (!(menu instanceof PowerJunctionMenu junctionMenu)) {
                continue;
            }
            if (!(level.getBlockEntity(junctionMenu.pos()) instanceof PowerJunctionBlockEntity be)) {
                continue;
            }
            boolean powered = be.canExtractExact(
                    com.bobby.bobbypipes.logistics.power.LogisticsPowerCosts.MIN_CHECK)
                    && !power.junctions().isEmpty();
            // Prefer component status from the attached pipe when known.
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                net.minecraft.core.BlockPos n = junctionMenu.pos().relative(d);
                if (power.isPowered(n)) {
                    powered = true;
                    break;
                }
            }
            PowerUsageSampler sampler = power.samplerFor(junctionMenu.pos());
            PowerUsageSampler.Sample[] history = new PowerUsageSampler.Sample[sampler.size()];
            for (int i = 0; i < history.length; i++) {
                history[i] = sampler.sampleAt(i);
            }
            PacketDistributor.sendToPlayer(player, new PowerJunctionSyncPayload(
                    junctionMenu.pos(),
                    be.energy(),
                    be.capacity(),
                    powered,
                    sampler.currentAccumulatedIn(),
                    sampler.currentAccumulatedOut(),
                    history));
        }
    }
}
