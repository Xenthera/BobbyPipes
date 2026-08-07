package com.bobby.bobbypipes.registry;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.LinkPipeBlock;
import com.bobby.bobbypipes.block.AutocraftMonitorBlock;
import com.bobby.bobbypipes.block.BasicPipeBlock;
import com.bobby.bobbypipes.block.ChunkLoaderBlock;
import com.bobby.bobbypipes.block.CraftingPipeBlock;
import com.bobby.bobbypipes.block.EnergyProviderPipeBlock;
import com.bobby.bobbypipes.block.EnergyRequestPipeBlock;
import com.bobby.bobbypipes.block.EnergySupplierPipeBlock;
import com.bobby.bobbypipes.block.FluidProviderPipeBlock;
import com.bobby.bobbypipes.block.FluidRequestPipeBlock;
import com.bobby.bobbypipes.block.FluidSupplierPipeBlock;
import com.bobby.bobbypipes.block.PassiveSupplierPipeBlock;
import com.bobby.bobbypipes.block.PatternTableBlock;
import com.bobby.bobbypipes.block.PipeBlock;
import com.bobby.bobbypipes.block.PowerJunctionBlock;
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

    /** Unrouted transport tube. Corridor fabric only - never a routing node. */
    public static final DeferredBlock<PipeBlock> PIPE = registerWithItem("pipe",
            props -> new PipeBlock(props
                    .strength(0.3f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /**
     * Pairable wormhole fabric. Same corridor role as {@link #PIPE}, plus a channel that
     * inserts a cost-1 virtual edge to its peer (same or other dimension).
     */
    public static final DeferredBlock<LinkPipeBlock> LINK_PIPE = registerWithItem("link_pipe",
            props -> new LinkPipeBlock(props
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

    /** Offers the energy storage touching it to the network. */
    public static final DeferredBlock<EnergyProviderPipeBlock> ENERGY_PROVIDER_PIPE =
            registerWithItem("energy_provider_pipe", props -> new EnergyProviderPipeBlock(props
                    .strength(0.3f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Offers the fluid tank touching it to the network. */
    public static final DeferredBlock<FluidProviderPipeBlock> FLUID_PROVIDER_PIPE =
            registerWithItem("fluid_provider_pipe", props -> new FluidProviderPipeBlock(props
                    .strength(0.3f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Requests items from the network and delivers them into the inventories touching it. */
    public static final DeferredBlock<RequestPipeBlock> REQUEST_PIPE =
            registerWithItem("request_pipe", props -> new RequestPipeBlock(props
                    .strength(0.3f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Requests FE from the network and delivers it into the energy storage touching it. */
    public static final DeferredBlock<EnergyRequestPipeBlock> ENERGY_REQUEST_PIPE =
            registerWithItem("energy_request_pipe", props -> new EnergyRequestPipeBlock(props
                    .strength(0.3f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Requests fluid from the network and delivers it into the tank touching it. */
    public static final DeferredBlock<FluidRequestPipeBlock> FLUID_REQUEST_PIPE =
            registerWithItem("fluid_request_pipe", props -> new FluidRequestPipeBlock(props
                    .strength(0.3f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Keeps its attached inventory stocked by pulling from the network. */
    public static final DeferredBlock<SupplierPipeBlock> SUPPLIER_PIPE =
            registerWithItem("supplier_pipe", props -> new SupplierPipeBlock(props
                    .strength(0.3f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Keeps its attached energy storage topped up by pulling FE from the network. */
    public static final DeferredBlock<EnergySupplierPipeBlock> ENERGY_SUPPLIER_PIPE =
            registerWithItem("energy_supplier_pipe", props -> new EnergySupplierPipeBlock(props
                    .strength(0.3f)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Keeps its attached tank topped up by pulling fluid from the network. */
    public static final DeferredBlock<FluidSupplierPipeBlock> FLUID_SUPPLIER_PIPE =
            registerWithItem("fluid_supplier_pipe", props -> new FluidSupplierPipeBlock(props
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

    /** Creative-only chunk force-loader. No recipe. */
    public static final DeferredBlock<ChunkLoaderBlock> CHUNK_LOADER =
            registerWithItem("chunk_loader", props -> new ChunkLoaderBlock(props
                    .strength(2.5f)
                    .sound(SoundType.METAL)));

    /** Logistics FE buffer that powers routing actions on the adjacent pipe component. */
    public static final DeferredBlock<PowerJunctionBlock> POWER_JUNCTION =
            registerWithItem("power_junction", props -> new PowerJunctionBlock(props
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
