package com.bobby.bobbypipes.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkPipeRegistryTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("overworld"));
    private static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("the_nether"));

    @Test
    @DisplayName("two endpoints may share a channel; a third is rejected")
    void pairOnly() {
        LinkPipeRegistry registry = new LinkPipeRegistry();
        PipeNodeId a = PipeNodeId.of(OVERWORLD, new BlockPos(0, 64, 0));
        PipeNodeId b = PipeNodeId.of(OVERWORLD, new BlockPos(100, 64, 0));
        PipeNodeId c = PipeNodeId.of(NETHER, new BlockPos(0, 64, 0));

        assertEquals(LinkClaimResult.OK, registry.claim(a, 7));
        assertEquals(LinkClaimResult.OK, registry.claim(b, 7));
        assertEquals(LinkClaimResult.CHANNEL_FULL, registry.claim(c, 7));

        assertEquals(1, registry.completePairs().size());
        assertEquals(Optional.of(b), registry.peerOf(a));
        assertTrue(registry.channelOf(c).isEmpty());
    }

    @Test
    @DisplayName("release frees a slot so another endpoint can join")
    void releaseThenReclaim() {
        LinkPipeRegistry registry = new LinkPipeRegistry();
        PipeNodeId a = PipeNodeId.of(OVERWORLD, new BlockPos(1, 1, 1));
        PipeNodeId b = PipeNodeId.of(OVERWORLD, new BlockPos(2, 2, 2));
        PipeNodeId c = PipeNodeId.of(OVERWORLD, new BlockPos(3, 3, 3));

        registry.claim(a, 1);
        registry.claim(b, 1);
        assertEquals(LinkClaimResult.CHANNEL_FULL, registry.claim(c, 1));

        registry.release(b);
        assertEquals(LinkClaimResult.OK, registry.claim(c, 1));
        assertEquals(Optional.of(c), registry.peerOf(a));
    }

    @Test
    @DisplayName("invalid channel ids are rejected")
    void invalidChannel() {
        LinkPipeRegistry registry = new LinkPipeRegistry();
        PipeNodeId a = PipeNodeId.of(OVERWORLD, new BlockPos(0, 0, 0));
        assertEquals(LinkClaimResult.INVALID_CHANNEL, registry.claim(a, 0));
        assertEquals(LinkClaimResult.INVALID_CHANNEL, registry.claim(a, -1));
    }

    @Test
    @DisplayName("claiming a new channel releases the previous one")
    void rechannelReleasesOld() {
        LinkPipeRegistry registry = new LinkPipeRegistry();
        PipeNodeId a = PipeNodeId.of(OVERWORLD, new BlockPos(0, 0, 0));
        PipeNodeId b = PipeNodeId.of(OVERWORLD, new BlockPos(1, 0, 0));
        registry.claim(a, 1);
        registry.claim(b, 1);
        assertEquals(LinkClaimResult.OK, registry.claim(a, 2));
        assertTrue(registry.peerOf(b).isEmpty());
        assertEquals(1, registry.channelOf(b).orElse(-1));
        assertEquals(2, registry.channelOf(a).orElse(-1));
    }

    @Test
    @DisplayName("pair claim survives conceptually when peer would be unloaded (channel stays full)")
    void pairClaimBlocksThirdWhilePeerAbsent() {
        // Registry does not track load itself; unload only drops the edge. A third claim
        // must still fail while both endpoints remain registered.
        LinkPipeRegistry registry = new LinkPipeRegistry();
        PipeNodeId a = PipeNodeId.of(OVERWORLD, new BlockPos(0, 64, 0));
        PipeNodeId b = PipeNodeId.of(NETHER, new BlockPos(0, 64, 0));
        PipeNodeId c = PipeNodeId.of(OVERWORLD, new BlockPos(50, 64, 0));
        assertEquals(LinkClaimResult.OK, registry.claim(a, 9));
        assertEquals(LinkClaimResult.OK, registry.claim(b, 9));
        assertEquals(LinkClaimResult.CHANNEL_FULL, registry.claim(c, 9));
        assertEquals(Optional.of(b), registry.peerOf(a));
    }

    @Test
    @DisplayName("nothing is loaded until a block entity says so")
    void nothingLoadedByDefault() {
        PipeNodeId nether = PipeNodeId.of(NETHER, new BlockPos(8, 64, 8));
        LinkPipeRegistry.markUnloaded(nether);
        // A fresh server has seen no onLoad, so every endpoint reads unloaded - which is
        // why a freshly loaded world settles on SEVERED rather than guessing LIVE.
        assertFalse(LinkPipeRegistry.isLoaded(nether));
    }

    @Test
    @DisplayName("a pair is live only while both ends are loaded")
    void liveNeedsBothEnds() {
        LinkPipeRegistry registry = new LinkPipeRegistry();
        PipeNodeId over = PipeNodeId.of(OVERWORLD, new BlockPos(0, 64, 0));
        PipeNodeId nether = PipeNodeId.of(NETHER, new BlockPos(8, 64, 8));
        assertEquals(LinkClaimResult.OK, registry.claim(over, 4));
        assertEquals(LinkClaimResult.OK, registry.claim(nether, 4));
        LinkPipeRegistry.markUnloaded(over);
        LinkPipeRegistry.markUnloaded(nether);

        assertFalse(registry.isLive(over), "neither end loaded");

        LinkPipeRegistry.markLoaded(over);
        assertFalse(registry.isLive(over),
                "the peer is still unloaded, so this end stays severed");

        LinkPipeRegistry.markLoaded(nether);
        assertTrue(registry.isLive(over), "both ends loaded");
        assertTrue(registry.isLive(nether), "and it is symmetric");

        // The peer going away severs immediately and stays severed - no flicker back.
        LinkPipeRegistry.markUnloaded(nether);
        assertFalse(registry.isLive(over));
        assertFalse(registry.isLive(over), "still severed on a second look");

        LinkPipeRegistry.markUnloaded(over);
    }

    @Test
    @DisplayName("loaded state is per endpoint, not per position or dimension")
    void loadedIsPerEndpoint() {
        PipeNodeId nether = PipeNodeId.of(NETHER, new BlockPos(8, 64, 8));
        PipeNodeId sameposOverworld = PipeNodeId.of(OVERWORLD, new BlockPos(8, 64, 8));
        LinkPipeRegistry.markUnloaded(sameposOverworld);

        LinkPipeRegistry.markLoaded(nether);
        assertTrue(LinkPipeRegistry.isLoaded(nether));
        assertFalse(LinkPipeRegistry.isLoaded(sameposOverworld),
                "same coordinates in another dimension is a different endpoint");

        LinkPipeRegistry.markUnloaded(nether);
    }

    @Test
    @DisplayName("unloading a dimension forgets its endpoints, so a reloaded world starts clean")
    void forgetDimensionClearsOnlyThatDimension() {
        PipeNodeId over = PipeNodeId.of(OVERWORLD, new BlockPos(0, 64, 0));
        PipeNodeId nether = PipeNodeId.of(NETHER, new BlockPos(8, 64, 8));
        LinkPipeRegistry.markLoaded(over);
        LinkPipeRegistry.markLoaded(nether);

        LinkPipeRegistry.forgetDimension(NETHER);
        assertFalse(LinkPipeRegistry.isLoaded(nether), "nether endpoints forgotten");
        assertTrue(LinkPipeRegistry.isLoaded(over), "the overworld is untouched");

        LinkPipeRegistry.forgetDimension(OVERWORLD);
        assertFalse(LinkPipeRegistry.isLoaded(over));
    }
}
