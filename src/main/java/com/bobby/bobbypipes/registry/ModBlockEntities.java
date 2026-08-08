package com.bobby.bobbypipes.registry;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.LinkPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.AutocraftMonitorBlockEntity;
import com.bobby.bobbypipes.block.entity.BasicPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.ChunkLoaderBlockEntity;
import com.bobby.bobbypipes.block.entity.CraftingPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.EnergySupplierPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.FluidSupplierPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.FuelGeneratorBlockEntity;
import com.bobby.bobbypipes.block.entity.PassiveSupplierPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.PatternTableBlockEntity;
import com.bobby.bobbypipes.block.entity.PipeBlockEntity;
import com.bobby.bobbypipes.block.entity.PowerJunctionBlockEntity;
import com.bobby.bobbypipes.block.entity.ProviderPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.SatellitePipeBlockEntity;
import com.bobby.bobbypipes.block.entity.SupplierPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.TwerkGeneratorBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, BobbyPipes.MOD_ID);

    /**
     * Cover holder for pipes that carry no block entity of their own.
     *
     * <p>Only created once such a pipe is covered; a bare transport pipe stays block-entity
     * free. Pipes that already have one (basic, provider, ...) inherit the same cover state
     * from {@link com.bobby.bobbypipes.block.entity.PipeBlockEntity} and do not use this type.
     */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PipeBlockEntity>> PIPE_COVER =
            BLOCK_ENTITIES.register("pipe_cover", () -> new BlockEntityType<>(
                    (pos, state) -> new PipeBlockEntity(ModBlockEntities.PIPE_COVER.get(), pos, state),
                    Set.of(ModBlocks.PIPE.get(),
                            ModBlocks.REQUEST_PIPE.get(),
                            ModBlocks.ENERGY_PROVIDER_PIPE.get(),
                            ModBlocks.ENERGY_REQUEST_PIPE.get(),
                            ModBlocks.FLUID_PROVIDER_PIPE.get(),
                            ModBlocks.FLUID_REQUEST_PIPE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BasicPipeBlockEntity>> BASIC_PIPE =
            BLOCK_ENTITIES.register("basic_pipe", () -> new BlockEntityType<>(
                    BasicPipeBlockEntity::new, Set.of(ModBlocks.BASIC_PIPE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ProviderPipeBlockEntity>> PROVIDER_PIPE =
            BLOCK_ENTITIES.register("provider_pipe", () -> new BlockEntityType<>(
                    ProviderPipeBlockEntity::new, Set.of(ModBlocks.PROVIDER_PIPE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PatternTableBlockEntity>> PATTERN_TABLE =
            BLOCK_ENTITIES.register("pattern_table", () -> new BlockEntityType<>(
                    PatternTableBlockEntity::new, Set.of(ModBlocks.PATTERN_TABLE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CraftingPipeBlockEntity>> CRAFTING_PIPE =
            BLOCK_ENTITIES.register("crafting_pipe", () -> new BlockEntityType<>(
                    CraftingPipeBlockEntity::new, Set.of(ModBlocks.CRAFTING_PIPE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SatellitePipeBlockEntity>> SATELLITE_PIPE =
            BLOCK_ENTITIES.register("satellite_pipe", () -> new BlockEntityType<>(
                    SatellitePipeBlockEntity::new, Set.of(ModBlocks.SATELLITE_PIPE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<LinkPipeBlockEntity>> LINK_PIPE =
            BLOCK_ENTITIES.register("link_pipe", () -> new BlockEntityType<>(
                    LinkPipeBlockEntity::new, Set.of(ModBlocks.LINK_PIPE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SupplierPipeBlockEntity>> SUPPLIER_PIPE =
            BLOCK_ENTITIES.register("supplier_pipe", () -> new BlockEntityType<>(
                    SupplierPipeBlockEntity::new, Set.of(ModBlocks.SUPPLIER_PIPE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PassiveSupplierPipeBlockEntity>> PASSIVE_SUPPLIER_PIPE =
            BLOCK_ENTITIES.register("passive_supplier_pipe", () -> new BlockEntityType<>(
                    PassiveSupplierPipeBlockEntity::new, Set.of(ModBlocks.PASSIVE_SUPPLIER_PIPE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EnergySupplierPipeBlockEntity>> ENERGY_SUPPLIER_PIPE =
            BLOCK_ENTITIES.register("energy_supplier_pipe", () -> new BlockEntityType<>(
                    EnergySupplierPipeBlockEntity::new, Set.of(ModBlocks.ENERGY_SUPPLIER_PIPE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FluidSupplierPipeBlockEntity>> FLUID_SUPPLIER_PIPE =
            BLOCK_ENTITIES.register("fluid_supplier_pipe", () -> new BlockEntityType<>(
                    FluidSupplierPipeBlockEntity::new, Set.of(ModBlocks.FLUID_SUPPLIER_PIPE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AutocraftMonitorBlockEntity>> AUTOCRAFT_MONITOR =
            BLOCK_ENTITIES.register("autocraft_monitor", () -> new BlockEntityType<>(
                    AutocraftMonitorBlockEntity::new, Set.of(ModBlocks.AUTOCRAFT_MONITOR.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChunkLoaderBlockEntity>> CHUNK_LOADER =
            BLOCK_ENTITIES.register("chunk_loader", () -> new BlockEntityType<>(
                    ChunkLoaderBlockEntity::new, Set.of(ModBlocks.CHUNK_LOADER.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PowerJunctionBlockEntity>> POWER_JUNCTION =
            BLOCK_ENTITIES.register("power_junction", () -> new BlockEntityType<>(
                    PowerJunctionBlockEntity::new, Set.of(ModBlocks.POWER_JUNCTION.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FuelGeneratorBlockEntity>> FUEL_GENERATOR =
            BLOCK_ENTITIES.register("fuel_generator", () -> new BlockEntityType<>(
                    FuelGeneratorBlockEntity::new, Set.of(ModBlocks.FUEL_GENERATOR.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TwerkGeneratorBlockEntity>> TWERK_GENERATOR =
            BLOCK_ENTITIES.register("twerk_generator", () -> new BlockEntityType<>(
                    TwerkGeneratorBlockEntity::new, Set.of(ModBlocks.TWERK_GENERATOR.get())));

    private ModBlockEntities() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
    }
}
