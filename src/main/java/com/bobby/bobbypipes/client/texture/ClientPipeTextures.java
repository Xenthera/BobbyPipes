package com.bobby.bobbypipes.client.texture;

import com.bobby.bobbypipes.BobbyPipes;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterSpriteSourcesEvent;

@EventBusSubscriber(modid = BobbyPipes.MOD_ID, value = Dist.CLIENT)
public final class ClientPipeTextures {

    private ClientPipeTextures() {
    }

    @SubscribeEvent
    public static void registerSpriteSources(RegisterSpriteSourcesEvent event) {
        event.register(
                Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "pipe_composite"),
                PipeCompositeSource.MAP_CODEC);
    }
}
