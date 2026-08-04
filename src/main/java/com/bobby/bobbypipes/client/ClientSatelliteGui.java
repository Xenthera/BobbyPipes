package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.client.screen.SatellitePipeScreen;
import com.bobby.bobbypipes.pipes.SatelliteNamingResult;
import net.minecraft.client.Minecraft;

public final class ClientSatelliteGui {

    private ClientSatelliteGui() {
    }

    public static void onNameResult(SatelliteNamingResult result, String name) {
        if (Minecraft.getInstance().screen instanceof SatellitePipeScreen screen) {
            screen.onNamingResult(result, name);
        }
    }
}
