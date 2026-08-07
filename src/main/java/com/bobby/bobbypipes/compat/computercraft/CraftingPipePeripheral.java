package com.bobby.bobbypipes.compat.computercraft;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.CraftingPipeBlockEntity;
import com.bobby.bobbypipes.craft.CraftPattern;
import com.bobby.bobbypipes.network.CraftJobManager;
import com.bobby.bobbypipes.network.PipeNetwork;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ComputerCraft peripheral for Crafting pipes — pattern info and local job status.
 */
public final class CraftingPipePeripheral implements IPeripheral {

    private final CraftingPipeBlockEntity pipe;

    public CraftingPipePeripheral(CraftingPipeBlockEntity pipe) {
        this.pipe = pipe;
    }

    @Override
    public String getType() {
        return BobbyPipes.MOD_ID + ":crafting_pipe";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof CraftingPipePeripheral p && p.pipe == pipe;
    }

    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getOutput() throws LuaException {
        server();
        ItemStack output = pipe.pattern().primaryOutput();
        if (output.isEmpty()) {
            return null;
        }
        return CcHelpers.itemEntry(ItemResource.of(output), output.getCount());
    }

    @LuaFunction(mainThread = true)
    public final boolean hasPattern() throws LuaException {
        server();
        return !pipe.pattern().isEmpty();
    }

    @LuaFunction(mainThread = true)
    public final @Nullable String getSatellite() throws LuaException {
        server();
        CraftPattern pattern = pipe.pattern();
        if (!pattern.hasSatellite()) {
            return null;
        }
        return pattern.satellite();
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> listJobs() throws LuaException {
        ServerLevel level = server();
        List<CraftJobManager.JobReport> reports =
                PipeNetwork.get(level).craftJobs().describe(level, PipeNetwork.get(level));
        List<Map<String, Object>> local = new ArrayList<>();
        for (CraftJobManager.JobReport report : reports) {
            if (!report.crafter().equals(pipe.getBlockPos())) {
                continue;
            }
            Map<String, Object> row = new HashMap<>(2);
            row.put("headline", report.headline());
            row.put("detail", report.detail());
            local.add(row);
        }
        Map<Integer, Map<String, Object>> out = new HashMap<>(local.size());
        for (int i = 0; i < local.size(); i++) {
            out.put(i + 1, local.get(i));
        }
        return out;
    }

    private ServerLevel server() throws LuaException {
        return CcHelpers.requireServer(pipe.getLevel());
    }
}
