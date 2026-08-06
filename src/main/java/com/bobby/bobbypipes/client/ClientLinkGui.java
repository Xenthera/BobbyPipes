package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.client.screen.LinkPipeScreen;
import com.bobby.bobbypipes.pipes.LinkChannelResult;
import net.minecraft.client.Minecraft;

public final class ClientLinkGui {

    private ClientLinkGui() {
    }

    public static void onChannelResult(LinkChannelResult result, int channel,
                                       boolean paired, boolean live) {
        if (Minecraft.getInstance().screen instanceof LinkPipeScreen screen) {
            screen.onChannelResult(result, channel, paired, live);
        }
    }
}
