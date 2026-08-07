package com.bobby.bobbypipes.compat.computercraft;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.logistics.FluidRequestService;
import com.bobby.bobbypipes.logistics.PipeNetwork;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ComputerCraft peripheral for the Fluid Request Pipe (no block entity — pos-based).
 */
public final class FluidRequestPipePeripheral implements IPeripheral {

    private final ServerLevel level;
    private final BlockPos pos;

    public FluidRequestPipePeripheral(ServerLevel level, BlockPos pos) {
        this.level = level;
        this.pos = pos.immutable();
    }

    @Override
    public String getType() {
        return BobbyPipes.MOD_ID + ":fluid_request_pipe";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof FluidRequestPipePeripheral p
                && p.level == level
                && p.pos.equals(pos);
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> getAvailable() throws LuaException {
        PipeNetwork network = network();
        List<FluidRequestService.Entry> catalog = FluidRequestService.catalog(level, network, pos);
        Map<Integer, Map<String, Object>> out = new HashMap<>(catalog.size());
        int i = 1;
        for (FluidRequestService.Entry entry : catalog) {
            out.put(i++, CcHelpers.fluidEntry(entry.fluid(), entry.amountMb()));
        }
        return out;
    }

    @LuaFunction(mainThread = true)
    public final int getFluidCount(String fluidId) throws LuaException {
        FluidResource fluid = CcHelpers.parseFluid(fluidId);
        PipeNetwork network = network();
        return FluidRequestService.catalog(level, network, pos).stream()
                .filter(e -> e.fluid().equals(fluid))
                .mapToInt(FluidRequestService.Entry::amountMb)
                .findFirst()
                .orElse(0);
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> request(String fluidId, int amountMb) throws LuaException {
        if (amountMb <= 0) {
            throw new LuaException("amount must be positive");
        }
        FluidResource fluid = CcHelpers.parseFluid(fluidId);
        PipeNetwork network = network();
        int shipped = FluidRequestService.request(level, network, pos, fluid, amountMb);
        Map<String, Object> result = new HashMap<>(3);
        result.put("shipped", shipped);
        result.put("requested", amountMb);
        result.put("complete", shipped >= amountMb);
        return result;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> canRequest(String fluidId, int amountMb) throws LuaException {
        if (amountMb <= 0) {
            throw new LuaException("amount must be positive");
        }
        int available = getFluidCount(fluidId);
        Map<String, Object> result = new HashMap<>(2);
        result.put("satisfiable", available >= amountMb);
        if (available < amountMb) {
            result.put("missing", amountMb - available);
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
