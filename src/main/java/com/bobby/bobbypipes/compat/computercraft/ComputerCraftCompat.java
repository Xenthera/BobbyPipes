package com.bobby.bobbypipes.compat.computercraft;

import com.bobby.bobbypipes.block.entity.CraftingPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.LinkPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.ProviderPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.SatellitePipeBlockEntity;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import com.bobby.bobbypipes.registry.ModBlocks;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import java.util.function.Supplier;

/**
 * Registers ComputerCraft peripherals when CC:Tweaked is loaded.
 *
 * <p>Request pipes have no block entity, so they use {@code registerBlock}; everything
 * else attaches to its block entity type.
 */
public final class ComputerCraftCompat {

    private ComputerCraftCompat() {
    }

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlock(
                PeripheralCapability.get(),
                (level, pos, state, be, side) ->
                        level instanceof ServerLevel server
                                ? new RequestPipePeripheral(server, pos)
                                : null,
                ModBlocks.REQUEST_PIPE.get());
        event.registerBlock(
                PeripheralCapability.get(),
                (level, pos, state, be, side) ->
                        level instanceof ServerLevel server
                                ? new EnergyRequestPipePeripheral(server, pos)
                                : null,
                ModBlocks.ENERGY_REQUEST_PIPE.get());
        event.registerBlock(
                PeripheralCapability.get(),
                (level, pos, state, be, side) ->
                        level instanceof ServerLevel server
                                ? new FluidRequestPipePeripheral(server, pos)
                                : null,
                ModBlocks.FLUID_REQUEST_PIPE.get());

        registerProvider(event, ModBlockEntities.PROVIDER_PIPE);
        registerCrafting(event, ModBlockEntities.CRAFTING_PIPE);
        registerLink(event, ModBlockEntities.LINK_PIPE);
        registerSatellite(event, ModBlockEntities.SATELLITE_PIPE);
    }

    private static void registerProvider(
            RegisterCapabilitiesEvent event,
            Supplier<BlockEntityType<ProviderPipeBlockEntity>> type) {
        event.registerBlockEntity(
                PeripheralCapability.get(),
                type.get(),
                (ProviderPipeBlockEntity pipe, Direction side) -> new ProviderPipePeripheral(pipe));
    }

    private static void registerCrafting(
            RegisterCapabilitiesEvent event,
            Supplier<BlockEntityType<CraftingPipeBlockEntity>> type) {
        event.registerBlockEntity(
                PeripheralCapability.get(),
                type.get(),
                (CraftingPipeBlockEntity pipe, Direction side) -> new CraftingPipePeripheral(pipe));
    }

    private static void registerLink(
            RegisterCapabilitiesEvent event,
            Supplier<BlockEntityType<LinkPipeBlockEntity>> type) {
        event.registerBlockEntity(
                PeripheralCapability.get(),
                type.get(),
                (LinkPipeBlockEntity pipe, Direction side) -> new LinkPipePeripheral(pipe));
    }

    private static void registerSatellite(
            RegisterCapabilitiesEvent event,
            Supplier<BlockEntityType<SatellitePipeBlockEntity>> type) {
        event.registerBlockEntity(
                PeripheralCapability.get(),
                type.get(),
                (SatellitePipeBlockEntity pipe, Direction side) -> new SatellitePipePeripheral(pipe));
    }
}
