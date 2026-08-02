package com.bobby.bobbypipes;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BobbyPipes — request-driven item routing for Minecraft.
 *
 * <p>A clean-room reimagining of the LogisticsPipes concept: pipe networks that deliver
 * items on demand rather than pushing them continuously, with modular chassis pipes and
 * recursive crafting resolution.
 *
 * <p>No code is derived from LogisticsPipes. See CLEANROOM.md.
 */
@Mod(BobbyPipes.MOD_ID)
public class BobbyPipes {

    public static final String MOD_ID = "bobbypipes";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public BobbyPipes(IEventBus modBus, ModContainer container) {
        LOGGER.info("BobbyPipes loading");
    }
}
