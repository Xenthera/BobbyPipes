package com.bobby.bobbypipes.testsupport;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Probe: can a plain unit test build real item identities without a running game?
 *
 * <p>Decides the shape of the crafting harness. If this passes, tests can drive the real
 * {@code CraftJobManager} with real {@link ItemResource} keys against fake inventories. If
 * it fails, the harness has to be generic over item identity instead.
 */
class BootstrapProbeTest {

    @Test
    @Disabled("Bootstrap.bootStrap() leaves item holders without bound data components in 26.1: ItemResource.of throws \"Components not bound yet\". Needs the component binding step before the crafting harness can build item identities.")
    void canBootstrapAndBuildItemResources() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        ItemResource log = ItemResource.of(Items.OAK_LOG);
        ItemResource plank = ItemResource.of(Items.OAK_PLANKS);

        assertFalse(log.isEmpty());
        assertEquals(log, ItemResource.of(Items.OAK_LOG), "same item must compare equal");
        assertNotEquals(log, plank);
        assertEquals(4, log.toStack(4).getCount());
    }
}
