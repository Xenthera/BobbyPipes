package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.client.screen.CraftingPipeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.List;

public final class ClientCraftingGui {

    private ClientCraftingGui() {
    }

    public static void onSatelliteList(BlockPos crafterPos, List<String> names) {
        if (Minecraft.getInstance().screen instanceof CraftingPipeScreen screen) {
            screen.onSatelliteList(crafterPos, names);
        }
    }
}
