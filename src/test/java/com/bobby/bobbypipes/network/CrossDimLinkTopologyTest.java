package com.bobby.bobbypipes.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-dimension link edges are modeled as cost-1 adjacencies on a {@link PipeNodeId}
 * lattice so DirectCorridors can bridge worlds the same way it bridges distant same-dim
 * links.
 */
class CrossDimLinkTopologyTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:overworld"));
    private static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:the_nether"));

    @Test
    @DisplayName("cross-dim virtual edge joins corridors across PipeNodeId dimensions")
    void crossDimVirtualEdgeJoinsCorridors() {
        PipeNodeId smartA = PipeNodeId.of(OVERWORLD, new BlockPos(0, 64, 0));
        PipeNodeId a = PipeNodeId.of(OVERWORLD, new BlockPos(1, 64, 0));
        PipeNodeId linkA = PipeNodeId.of(OVERWORLD, new BlockPos(2, 64, 0));
        PipeNodeId linkB = PipeNodeId.of(NETHER, new BlockPos(10, 64, 10));
        PipeNodeId b = PipeNodeId.of(NETHER, new BlockPos(11, 64, 10));
        PipeNodeId smartB = PipeNodeId.of(NETHER, new BlockPos(12, 64, 10));

        Topology<PipeNodeId> lattice = Topology.<PipeNodeId>builder()
                .link(smartA, a)
                .link(a, linkA)
                .link(linkA, linkB) // wormhole
                .link(linkB, b)
                .link(b, smartB)
                .build();
        Set<PipeNodeId> smart = Set.of(smartA, smartB);
        Topology<PipeNodeId> transit = DirectCorridors.transitTopology(lattice, smart);
        assertTrue(RoutingSnapshot.of(transit, 1).canReach(smartA, smartB));
        assertEquals(1, transit.neighbours(linkA).get(linkB).intValue());
    }
}
