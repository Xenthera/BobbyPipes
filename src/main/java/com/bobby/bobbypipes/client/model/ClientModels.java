package com.bobby.bobbypipes.client.model;

import com.bobby.bobbypipes.BobbyPipes;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterBlockStateModels;

/** Makes this mod's custom block models addressable from blockstate JSON. */
@EventBusSubscriber(modid = BobbyPipes.MOD_ID, value = Dist.CLIENT)
public final class ClientModels {

    public static final Identifier CHAMELEON_COVER =
            Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "chameleon_cover");

    private ClientModels() {
    }

    @SubscribeEvent
    public static void registerBlockStateModels(RegisterBlockStateModels event) {
        event.registerModel(CHAMELEON_COVER, ChameleonCoverModel.Unbaked.CODEC);
    }
}
