package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.PipeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Drives network rebuilds off the level tick and drops state when a level unloads.
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
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        PipeNetwork network = PipeNetwork.get(level);
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            BlockPos pos = be.getBlockPos();
            if (!(level.getBlockState(pos).getBlock() instanceof PipeBlock)) {
                continue;
            }
            // Skip pipes already in the live graph so exploring loaded chunks does not
            // constantly dirty the cache. Missing pipes (boot / newly generated) seed it.
            if (!network.routes().contains(pos)) {
                network.invalidate(pos);
            }
            return;
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            PipeNetwork.forget(level);
        }
    }
}
