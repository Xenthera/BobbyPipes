package com.bobby.bobbypipes.compat.computercraft;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.ProviderPipeBlockEntity;
import com.bobby.bobbypipes.logistics.ProviderAccess;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * ComputerCraft peripheral for Provider pipes — stock peek only (network owns extracts).
 */
public final class ProviderPipePeripheral implements IPeripheral {

    private final ProviderPipeBlockEntity pipe;

    public ProviderPipePeripheral(ProviderPipeBlockEntity pipe) {
        this.pipe = pipe;
    }

    @Override
    public String getType() {
        return BobbyPipes.MOD_ID + ":provider_pipe";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof ProviderPipePeripheral p && p.pipe == pipe;
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> list() throws LuaException {
        ServerLevel level = server();
        Map<ItemResource, Integer> stock = ProviderAccess.summarize(level, pipe.getBlockPos());
        Map<Integer, Map<String, Object>> out = new HashMap<>(stock.size());
        int i = 1;
        for (Map.Entry<ItemResource, Integer> entry : stock.entrySet()) {
            out.put(i++, CcHelpers.itemEntry(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    @LuaFunction(mainThread = true)
    public final int getItemCount(String itemId) throws LuaException {
        ServerLevel level = server();
        ItemResource item = CcHelpers.parseItem(itemId);
        return ProviderAccess.countUnclaimed(level, pipe.getBlockPos(), item, java.util.Set.of());
    }

    private ServerLevel server() throws LuaException {
        return CcHelpers.requireServer(pipe.getLevel());
    }
}
