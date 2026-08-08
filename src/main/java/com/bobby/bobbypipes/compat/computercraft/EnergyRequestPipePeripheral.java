package com.bobby.bobbypipes.compat.computercraft;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.logistics.EnergyRequestService;
import com.bobby.bobbypipes.logistics.PipeNetwork;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * ComputerCraft peripheral for the Energy Request Pipe (no block entity - pos-based).
 */
public final class EnergyRequestPipePeripheral implements IPeripheral {

    private final ServerLevel level;
    private final BlockPos pos;

    public EnergyRequestPipePeripheral(ServerLevel level, BlockPos pos) {
        this.level = level;
        this.pos = pos.immutable();
    }

    @Override
    public String getType() {
        return BobbyPipes.MOD_ID + ":energy_request_pipe";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof EnergyRequestPipePeripheral p
                && p.level == level
                && p.pos.equals(pos);
    }

    @LuaFunction(mainThread = true)
    public final int getAvailable() throws LuaException {
        PipeNetwork network = network();
        return EnergyRequestService.availableFe(level, network, pos);
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> request(int amountFe) throws LuaException {
        if (amountFe <= 0) {
            throw new LuaException("amount must be positive");
        }
        PipeNetwork network = network();
        int shipped = EnergyRequestService.request(level, network, pos, amountFe);
        Map<String, Object> result = new HashMap<>(3);
        result.put("shipped", shipped);
        result.put("requested", amountFe);
        result.put("complete", shipped >= amountFe);
        return result;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> canRequest(int amountFe) throws LuaException {
        if (amountFe <= 0) {
            throw new LuaException("amount must be positive");
        }
        int available = getAvailable();
        Map<String, Object> result = new HashMap<>(2);
        result.put("satisfiable", available >= amountFe);
        if (available < amountFe) {
            result.put("missing", amountFe - available);
        }
        return result;
    }

    private PipeNetwork network() throws LuaException {
        PipeNetwork network = PipeNetwork.get(level);
        if (!network.isDirty() && network.routes().contains(pos)) {
            return network;
        }
        if (!network.rebuildNow(pos).contains(pos)) {
            throw new LuaException("No pipe network at this position");
        }
        return network;
    }
}
