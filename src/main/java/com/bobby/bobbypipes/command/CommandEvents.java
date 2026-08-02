package com.bobby.bobbypipes.command;

import com.bobby.bobbypipes.BobbyPipes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = BobbyPipes.MOD_ID)
public final class CommandEvents {

    private CommandEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        NetworkCommand.register(event.getDispatcher());
    }
}
