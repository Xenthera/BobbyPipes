package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.network.payload.PipeProbePayload;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/** Client cache for the Pipe Goggles billboard. */
public final class ClientPipeProbe {

    private static BlockPos pos;
    private static List<String> lines = List.of();

    private ClientPipeProbe() {
    }

    public static void handle(PipeProbePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            pos = payload.pos().immutable();
            lines = List.copyOf(payload.lines());
        });
    }

    public static void clear() {
        pos = null;
        lines = List.of();
    }

    public static BlockPos pos() {
        return pos;
    }

    public static List<String> lines() {
        return lines;
    }

    public static boolean hasData() {
        return pos != null && !lines.isEmpty();
    }
}
