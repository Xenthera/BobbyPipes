package com.bobby.bobbypipes.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    private static final ResourceKey<Level> END =
            ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:the_end"));

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

    /** Two nodes joined into one bridge snapshot. */
    private static RoutingSnapshot<PipeNodeId> bridge(PipeNodeId a, PipeNodeId b) {
        return RoutingSnapshot.of(Topology.<PipeNodeId>builder().link(a, b).build(), 1);
    }

    @Test
    @DisplayName("a component grows across a link into the peer dimension")
    void componentCrossesOneLink() {
        PipeNodeId over = PipeNodeId.of(OVERWORLD, new BlockPos(0, 64, 0));
        PipeNodeId nether = PipeNodeId.of(NETHER, new BlockPos(10, 64, 10));

        // The monitor only ever knows its own side; without this the far crafter is
        // invisible and the network reads as having no crafts on it.
        Set<PipeNodeId> reached = CrossDimPipeGraph.expandAcrossLinks(
                Set.of(over), List.of(bridge(over, nether)));

        assertTrue(reached.contains(over));
        assertTrue(reached.contains(nether), "the far end of the link is on the same network");
    }

    @Test
    @DisplayName("chained bridges are followed to a fixpoint, not one hop")
    void componentFollowsChainedBridges() {
        PipeNodeId over = PipeNodeId.of(OVERWORLD, new BlockPos(0, 64, 0));
        PipeNodeId nether = PipeNodeId.of(NETHER, new BlockPos(10, 64, 10));
        PipeNodeId end = PipeNodeId.of(END, new BlockPos(20, 64, 20));

        // Separate snapshots: reaching the End means noticing the Nether first, which a
        // single pass over the bridge set would miss depending on iteration order.
        Set<PipeNodeId> reached = CrossDimPipeGraph.expandAcrossLinks(
                Set.of(over), List.of(bridge(nether, end), bridge(over, nether)));

        assertTrue(reached.contains(nether));
        assertTrue(reached.contains(end), "a second link hop is still the same network");
    }

    @Test
    @DisplayName("a chain out to another dimension and back is one network")
    void chainLeavesAndReturns() {
        // request pipe -> link -> nether run -> link -> crafters, so both ends of the journey
        // are in the overworld but only connected through the nether.
        PipeNodeId request = PipeNodeId.of(OVERWORLD, new BlockPos(0, 64, 0));
        PipeNodeId outA = PipeNodeId.of(OVERWORLD, new BlockPos(1, 64, 0));
        PipeNodeId outB = PipeNodeId.of(NETHER, new BlockPos(50, 64, 50));
        PipeNodeId backA = PipeNodeId.of(NETHER, new BlockPos(54, 64, 50));
        PipeNodeId backB = PipeNodeId.of(OVERWORLD, new BlockPos(9, 64, 0));
        PipeNodeId crafter = PipeNodeId.of(OVERWORLD, new BlockPos(10, 64, 0));

        // Two bridges, one per channel. Neither contains both the request pipe and the
        // crafter, and both of those sit in the same dimension.
        RoutingSnapshot<PipeNodeId> first = RoutingSnapshot.of(Topology.<PipeNodeId>builder()
                .link(request, outA).link(outA, outB).link(outB, backA).build(), 1);
        RoutingSnapshot<PipeNodeId> second = RoutingSnapshot.of(Topology.<PipeNodeId>builder()
                .link(backA, backB).link(backB, crafter).build(), 1);

        Set<PipeNodeId> reached = CrossDimPipeGraph.expandAcrossLinks(
                Set.of(request), List.of(first, second));

        assertTrue(reached.contains(outB), "the nether run is on the network");
        assertTrue(reached.contains(crafter),
                "and so is the crafter the second link comes back to, in the starting dimension");
    }

    @Test
    @DisplayName("an unrelated bridge does not drag its nodes onto this network")
    void unrelatedBridgeIsIgnored() {
        PipeNodeId over = PipeNodeId.of(OVERWORLD, new BlockPos(0, 64, 0));
        PipeNodeId strangerA = PipeNodeId.of(OVERWORLD, new BlockPos(500, 64, 500));
        PipeNodeId strangerB = PipeNodeId.of(NETHER, new BlockPos(600, 64, 600));

        Set<PipeNodeId> reached = CrossDimPipeGraph.expandAcrossLinks(
                Set.of(over), List.of(bridge(strangerA, strangerB)));

        assertEquals(Set.of(over), reached, "someone else's link pair is someone else's network");
    }
}
