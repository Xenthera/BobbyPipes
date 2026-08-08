package com.bobby.bobbypipes.compat.computercraft;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.SatellitePipeBlockEntity;
import com.bobby.bobbypipes.logistics.PipeNetwork;
import com.bobby.bobbypipes.logistics.SatelliteLookup;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * ComputerCraft peripheral for Satellite pipes - name get/set with duplicate checks.
 */
public final class SatellitePipePeripheral implements IPeripheral {

    private final SatellitePipeBlockEntity pipe;

    public SatellitePipePeripheral(SatellitePipeBlockEntity pipe) {
        this.pipe = pipe;
    }

    @Override
    public String getType() {
        return BobbyPipes.MOD_ID + ":satellite_pipe";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof SatellitePipePeripheral p && p.pipe == pipe;
    }

    @LuaFunction(mainThread = true)
    public final String getName() throws LuaException {
        server();
        return pipe.satelliteName();
    }

    @LuaFunction(mainThread = true)
    public final boolean setName(String name) throws LuaException {
        ServerLevel level = server();
        String requested = name == null ? "" : name.trim();
        if (requested.isBlank()) {
            throw new LuaException("Satellite name cannot be blank");
        }
        if (SatelliteLookup.isDuplicateName(
                level, PipeNetwork.get(level), pipe.getBlockPos(), requested)) {
            throw new LuaException("Satellite name already in use on this network: " + requested);
        }
        pipe.setSatelliteName(requested);
        return true;
    }

    private ServerLevel server() throws LuaException {
        return CcHelpers.requireServer(pipe.getLevel());
    }
}
