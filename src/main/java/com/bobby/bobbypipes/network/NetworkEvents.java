package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.BobbyPipes;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            PipeNetwork.forget(level);
        }
    }
}
