package com.bobby.bobbypipes.compat.computercraft;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.LinkPipeBlock;
import com.bobby.bobbypipes.block.LinkStatus;
import com.bobby.bobbypipes.block.entity.LinkPipeBlockEntity;
import com.bobby.bobbypipes.logistics.LinkClaimResult;
import com.bobby.bobbypipes.logistics.LinkPipeRegistry;
import com.bobby.bobbypipes.logistics.PipeNodeId;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ComputerCraft peripheral for Link pipes — channel and pair status.
 */
public final class LinkPipePeripheral implements IPeripheral {

    private final LinkPipeBlockEntity pipe;

    public LinkPipePeripheral(LinkPipeBlockEntity pipe) {
        this.pipe = pipe;
    }

    @Override
    public String getType() {
        return BobbyPipes.MOD_ID + ":link_pipe";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof LinkPipePeripheral p && p.pipe == pipe;
    }

    @LuaFunction(mainThread = true)
    public final int getChannel() throws LuaException {
        server();
        return pipe.channel();
    }

    @LuaFunction(mainThread = true)
    public final boolean setChannel(int channel) throws LuaException {
        server();
        LinkClaimResult result = pipe.setChannel(channel);
        if (result == LinkClaimResult.CHANNEL_FULL) {
            throw new LuaException("Channel " + channel + " already has two endpoints");
        }
        if (result == LinkClaimResult.INVALID_CHANNEL) {
            throw new LuaException("Invalid channel");
        }
        return true;
    }

    @LuaFunction(mainThread = true)
    public final String getStatus() throws LuaException {
        ServerLevel level = server();
        LinkStatus status = LinkPipeBlock.statusOf(level, pipe.getBlockPos());
        // Plan-facing names: waiting / linked / severed (+ idle when unpaired).
        return switch (status) {
            case LIVE -> "linked";
            case WAITING -> "waiting";
            case SEVERED -> "severed";
            case IDLE -> "idle";
        };
    }

    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getPeer() throws LuaException {
        ServerLevel level = server();
        if (!pipe.isPaired()) {
            return null;
        }
        Optional<PipeNodeId> peer = LinkPipeRegistry.get(level)
                .peerOf(PipeNodeId.of(level, pipe.getBlockPos()));
        if (peer.isEmpty()) {
            return null;
        }
        PipeNodeId node = peer.get();
        ServerLevel peerLevel = LinkPipeRegistry.levelOf(level.getServer(), node);
        if (peerLevel == null || !peerLevel.hasChunkAt(node.pos())) {
            return null;
        }
        Map<String, Object> map = new HashMap<>(4);
        map.put("dimension", node.dimensionLocation().toString());
        map.put("x", node.pos().getX());
        map.put("y", node.pos().getY());
        map.put("z", node.pos().getZ());
        return map;
    }

    private ServerLevel server() throws LuaException {
        return CcHelpers.requireServer(pipe.getLevel());
    }
}
