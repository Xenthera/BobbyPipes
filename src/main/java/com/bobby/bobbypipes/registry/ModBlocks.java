package com.bobby.bobbypipes.registry;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.PipeBlock;
import com.bobby.bobbypipes.block.ProviderPipeBlock;
import com.bobby.bobbypipes.block.RequestPipeBlock;
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
     * The transport pipe. Still drawn as a plain cube; the connected model comes with the
     * rendering work later.
     */
    public static final DeferredBlock<PipeBlock> PIPE = registerWithItem("pipe",
            props -> new PipeBlock(props
                    .strength(0.3f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Offers the inventories touching it to the network. */
    public static final DeferredBlock<ProviderPipeBlock> PROVIDER_PIPE =
            registerWithItem("provider_pipe", props -> new ProviderPipeBlock(props
                    .strength(0.3f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Requests items from the network and delivers them into the inventories touching it. */
    public static final DeferredBlock<RequestPipeBlock> REQUEST_PIPE =
            registerWithItem("request_pipe", props -> new RequestPipeBlock(props
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
