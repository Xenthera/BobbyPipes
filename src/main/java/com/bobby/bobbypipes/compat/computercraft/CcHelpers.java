package com.bobby.bobbypipes.compat.computercraft;

import dan200.computercraft.api.lua.LuaException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared parsing and detail maps for BobbyPipes ComputerCraft peripherals.
 */
final class CcHelpers {

    private CcHelpers() {
    }

    static ServerLevel requireServer(Level level) throws LuaException {
        if (!(level instanceof ServerLevel serverLevel)) {
            throw new LuaException("Peripheral is not on the server");
        }
        return serverLevel;
    }

    static ItemResource parseItem(String itemId) throws LuaException {
        if (itemId == null || itemId.isBlank()) {
            throw new LuaException("Expected item id (e.g. minecraft:oak_log)");
        }
        Identifier id;
        try {
            id = Identifier.parse(itemId);
        } catch (RuntimeException e) {
            throw new LuaException("Invalid item id: " + itemId);
        }
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) {
            throw new LuaException("Unknown item: " + itemId);
        }
        ItemResource resource = ItemResource.of(item);
        if (resource.isEmpty()) {
            throw new LuaException("Unknown item: " + itemId);
        }
        return resource;
    }

    static FluidResource parseFluid(String fluidId) throws LuaException {
        if (fluidId == null || fluidId.isBlank()) {
            throw new LuaException("Expected fluid id (e.g. minecraft:water)");
        }
        Identifier id;
        try {
            id = Identifier.parse(fluidId);
        } catch (RuntimeException e) {
            throw new LuaException("Invalid fluid id: " + fluidId);
        }
        Fluid fluid = BuiltInRegistries.FLUID.getOptional(id).orElse(null);
        if (fluid == null) {
            throw new LuaException("Unknown fluid: " + fluidId);
        }
        FluidResource resource = FluidResource.of(fluid);
        if (resource.isEmpty()) {
            throw new LuaException("Unknown fluid: " + fluidId);
        }
        return resource;
    }

    static String itemName(ItemResource item) {
        return BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
    }

    static String fluidName(FluidResource fluid) {
        return BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString();
    }

    static Map<String, Object> itemEntry(ItemResource item, int count) {
        Map<String, Object> map = new HashMap<>(2);
        map.put("name", itemName(item));
        map.put("count", count);
        return map;
    }

    static Map<String, Object> itemCatalogEntry(ItemResource item, int count, boolean craftable) {
        Map<String, Object> map = itemEntry(item, count);
        map.put("craftable", craftable);
        return map;
    }

    static Map<String, Object> fluidEntry(FluidResource fluid, int amountMb) {
        Map<String, Object> map = new HashMap<>(2);
        map.put("name", fluidName(fluid));
        map.put("count", amountMb);
        return map;
    }
}
