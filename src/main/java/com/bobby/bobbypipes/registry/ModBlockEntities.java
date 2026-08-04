package com.bobby.bobbypipes.registry;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.AutocraftMonitorBlockEntity;
import com.bobby.bobbypipes.block.entity.BasicPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.CraftingPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.PassiveSupplierPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.PatternTableBlockEntity;
import com.bobby.bobbypipes.block.entity.SatellitePipeBlockEntity;
import com.bobby.bobbypipes.block.entity.SupplierPipeBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, BobbyPipes.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BasicPipeBlockEntity>> BASIC_PIPE =
            BLOCK_ENTITIES.register("basic_pipe", () -> new BlockEntityType<>(
                    BasicPipeBlockEntity::new, Set.of(ModBlocks.BASIC_PIPE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PatternTableBlockEntity>> PATTERN_TABLE =
            BLOCK_ENTITIES.register("pattern_table", () -> new BlockEntityType<>(
                    PatternTableBlockEntity::new, Set.of(ModBlocks.PATTERN_TABLE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CraftingPipeBlockEntity>> CRAFTING_PIPE =
            BLOCK_ENTITIES.register("crafting_pipe", () -> new BlockEntityType<>(
                    CraftingPipeBlockEntity::new, Set.of(ModBlocks.CRAFTING_PIPE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SatellitePipeBlockEntity>> SATELLITE_PIPE =
            BLOCK_ENTITIES.register("satellite_pipe", () -> new BlockEntityType<>(
                    SatellitePipeBlockEntity::new, Set.of(ModBlocks.SATELLITE_PIPE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SupplierPipeBlockEntity>> SUPPLIER_PIPE =
            BLOCK_ENTITIES.register("supplier_pipe", () -> new BlockEntityType<>(
                    SupplierPipeBlockEntity::new, Set.of(ModBlocks.SUPPLIER_PIPE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PassiveSupplierPipeBlockEntity>> PASSIVE_SUPPLIER_PIPE =
            BLOCK_ENTITIES.register("passive_supplier_pipe", () -> new BlockEntityType<>(
                    PassiveSupplierPipeBlockEntity::new, Set.of(ModBlocks.PASSIVE_SUPPLIER_PIPE.get())));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AutocraftMonitorBlockEntity>> AUTOCRAFT_MONITOR =
            BLOCK_ENTITIES.register("autocraft_monitor", () -> new BlockEntityType<>(
                    AutocraftMonitorBlockEntity::new, Set.of(ModBlocks.AUTOCRAFT_MONITOR.get())));

    private ModBlockEntities() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
    }
}
