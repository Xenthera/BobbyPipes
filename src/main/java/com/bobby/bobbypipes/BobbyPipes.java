package com.bobby.bobbypipes;

import com.bobby.bobbypipes.registry.ModBlockEntities;
import com.bobby.bobbypipes.registry.ModBlocks;
import com.bobby.bobbypipes.registry.ModCreativeTabs;
import com.bobby.bobbypipes.registry.ModDataComponents;
import com.bobby.bobbypipes.registry.ModItems;
import com.bobby.bobbypipes.registry.ModMenus;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BobbyPipes - request-driven item routing for Minecraft.
 *
 * <p>Pipe networks that deliver items on demand rather than pushing them continuously,
 * with modular chassis pipes and recursive crafting resolution.
 */
@Mod(BobbyPipes.MOD_ID)
public class BobbyPipes {

    public static final String MOD_ID = "bobbypipes";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public BobbyPipes(IEventBus modEventBus, ModContainer container) {
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModItems.register(modEventBus);
        ModDataComponents.register(modEventBus);
        ModMenus.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
    }
}
