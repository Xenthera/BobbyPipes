package com.bobby.bobbypipes.registry;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.AutocraftMonitorBlock;
import com.bobby.bobbypipes.block.BasicPipeBlock;
import com.bobby.bobbypipes.block.CraftingPipeBlock;
import com.bobby.bobbypipes.block.PassiveSupplierPipeBlock;
import com.bobby.bobbypipes.block.PatternTableBlock;
import com.bobby.bobbypipes.block.PipeBlock;
import com.bobby.bobbypipes.block.ProviderPipeBlock;
import com.bobby.bobbypipes.block.RequestPipeBlock;
import com.bobby.bobbypipes.block.SatellitePipeBlock;
import com.bobby.bobbypipes.block.SupplierPipeBlock;
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

    /** Unrouted transport tube. Corridor fabric only  -  never a routing node. */
    public static final DeferredBlock<PipeBlock> PIPE = registerWithItem("pipe",
            props -> new PipeBlock(props
                    .strength(0.3f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /**
     * Routed pipe with no inventory role. Place at junctions of transport pipe so the
     * router graph stays connected.
     */
    public static final DeferredBlock<BasicPipeBlock> BASIC_PIPE =
            registerWithItem("basic_pipe", props -> new BasicPipeBlock(props
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

    /** Keeps its attached inventory stocked by pulling from the network. */
    public static final DeferredBlock<SupplierPipeBlock> SUPPLIER_PIPE =
            registerWithItem("supplier_pipe", props -> new SupplierPipeBlock(props
                    .strength(0.3f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Takes items the network has nowhere else to put, up to a configured target. */
    public static final DeferredBlock<PassiveSupplierPipeBlock> PASSIVE_SUPPLIER_PIPE =
            registerWithItem("passive_supplier_pipe", props -> new PassiveSupplierPipeBlock(props
                    .strength(0.3f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Hosts a craft pattern and crafts into its adjacent inventory. */
    public static final DeferredBlock<CraftingPipeBlock> CRAFTING_PIPE =
            registerWithItem("crafting_pipe", props -> new CraftingPipeBlock(props
                    .strength(0.3f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Named satellite buffer for crafting pipe ingredient slots. */
    public static final DeferredBlock<SatellitePipeBlock> SATELLITE_PIPE =
            registerWithItem("satellite_pipe", props -> new SatellitePipeBlock(props
                    .strength(0.3f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Authors craft patterns for import onto crafting pipes. */
    public static final DeferredBlock<PatternTableBlock> PATTERN_TABLE =
            registerWithItem("pattern_table", props -> new PatternTableBlock(props
                    .strength(2.5f)
                    .sound(SoundType.WOOD)));

    /** Scrollable view of the autocraft queue; place next to a smart pipe. */
    public static final DeferredBlock<AutocraftMonitorBlock> AUTOCRAFT_MONITOR =
            registerWithItem("autocraft_monitor", props -> new AutocraftMonitorBlock(props
                    .strength(2.5f)
                    .sound(SoundType.METAL)));

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
