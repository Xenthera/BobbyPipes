package com.bobby.bobbypipes.registry;

import com.bobby.bobbypipes.BobbyPipes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Function;

public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(BobbyPipes.MOD_ID);

    /**
     * Placeholder for the transport pipe. A plain cube for now; the real pipe gets a
     * connected model and a block entity in the routing phase.
     */
    public static final DeferredBlock<Block> PIPE = registerWithItem("pipe",
            props -> new Block(props
                    .strength(0.3f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    private ModBlocks() {
    }

    private static <T extends Block> DeferredBlock<T> registerWithItem(
            String name, Function<BlockBehaviour.Properties, T> factory) {
        DeferredBlock<T> block = BLOCKS.registerBlock(name, factory);
        ModItems.ITEMS.registerItem(name,
                props -> new BlockItem(block.get(), props.useBlockDescriptionPrefix()));
        return block;
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
