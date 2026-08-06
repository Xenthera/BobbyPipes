package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.LinkPipeBlock;
import com.bobby.bobbypipes.block.PipeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Drives network rebuilds off the level tick and drops state when a level unloads.
 *
 * <p>Link-pipe pairs keep their channel claim across chunk unload; only the virtual edge
 * severs until both ends are loaded again.
 */
@EventBusSubscriber(modid = BobbyPipes.MOD_ID)
public final class NetworkEvents {

    private NetworkEvents() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            // Cheap when clean: one reference read. The solve only happens on the tick
            // after the pipe layout actually changed.
            PipeNetwork.get(level).tick();
        }
    }

    /**
     * World load never fires pipe place events, so seed the network from pipe block
     * entities as their chunks come in. One seed per chunk is enough; connected pipes
     * are discovered by the lattice scan, and pending seeds merge across chunks.
     *
     * <p>Link pipes always invalidate their pair so a returning peer restores the
     * wormhole edge (and a missing peer keeps it severed). Seeds every missing pipe in
     * the chunk - not just the first - so disconnected components in one chunk all join.
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        LinkPipeRegistry registry = LinkPipeRegistry.get(level);
        for (PipeNodeId endpoint : registry.endpointsInChunk(level, chunk.getPos())) {
            registry.onEndpointChunkChange(level.getServer(), endpoint);
            // Peer may still be WAITING/SEVERED until this end's BE onLoad reclaims; status
            // refresh runs again from LinkPipeBlockEntity.onLoad after claim.
            if (level.hasChunkAt(endpoint.pos())) {
                LinkPipeBlock.refreshLinkStatus(level, endpoint.pos());
            }
        }

        PipeNetwork network = PipeNetwork.get(level);
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            BlockPos pos = be.getBlockPos();
            if (!(level.getBlockState(pos).getBlock() instanceof PipeBlock)) {
                continue;
            }
            if (!network.routes().contains(pos)) {
                network.invalidate(pos);
            }
        }
    }

    /**
     * Sever the virtual edge on the still-loaded peer when a link endpoint's chunk goes
     * away. The channel pair is not released.
     */
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ChunkPos chunkPos = event.getChunk().getPos();
        LinkPipeRegistry registry = LinkPipeRegistry.get(level);
        for (PipeNodeId endpoint : registry.endpointsInChunk(level, chunkPos)) {
            registry.onEndpointChunkChange(level.getServer(), endpoint);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LinkPipeRegistry.get(level).onDimensionUnload(level.getServer(), level.dimension());
            PipeNetwork.forget(level);
        }
    }
}
