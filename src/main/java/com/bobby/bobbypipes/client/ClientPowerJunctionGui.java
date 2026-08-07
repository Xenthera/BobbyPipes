package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.menu.PowerJunctionMenu;
import com.bobby.bobbypipes.network.payload.PowerJunctionSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;

public final class ClientPowerJunctionGui {

    private ClientPowerJunctionGui() {
    }

    public static void handle(PowerJunctionSyncPayload payload) {
        AbstractContainerMenu menu = Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.containerMenu
                : null;
        if (menu instanceof PowerJunctionMenu junctionMenu) {
            junctionMenu.applySync(payload);
        }
    }
}
