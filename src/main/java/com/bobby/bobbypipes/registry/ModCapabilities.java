package com.bobby.bobbypipes.registry;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.BasicPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.PatternTableBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

@EventBusSubscriber(modid = BobbyPipes.MOD_ID)
public final class ModCapabilities {

    private ModCapabilities() {
    }

    @SubscribeEvent
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.Item.BLOCK,
                ModBlockEntities.PATTERN_TABLE.get(),
                (be, side) -> be.itemHandler());

        // Lets a hopper, dropper, or another mod's pipe push items into the network. The
        // handler is insert only, so nothing can drain the network by pulling on a pipe.
        event.registerBlockEntity(
                Capabilities.Item.BLOCK,
                ModBlockEntities.BASIC_PIPE.get(),
                (be, side) -> be.itemHandler());

        // Plain pipe accepts items too, but they drift rather than being routed. No block
        // entity is involved, so a long run of pipe stays free.
        event.registerBlock(
                Capabilities.Item.BLOCK,
                // The context side is the face being touched, which is the face the item
                // enters through, so it is already the direction it came from. Passing the
                // opposite would have let an item turn straight back into the hopper.
                (level, pos, state, be, side) ->
                        level instanceof net.minecraft.server.level.ServerLevel serverLevel
                                ? new com.bobby.bobbypipes.network.PipeDriftIntake(
                                        serverLevel, pos, side)
                                : null,
                ModBlocks.PIPE.get());
    }
}
