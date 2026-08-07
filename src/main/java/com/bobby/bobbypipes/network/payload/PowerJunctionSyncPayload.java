package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.client.ClientPowerJunctionGui;
import com.bobby.bobbypipes.logistics.power.PowerSpendKind;
import com.bobby.bobbypipes.logistics.power.PowerUsageSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of a power junction buffer and usage history for the GUI graph.
 */
public record PowerJunctionSyncPayload(
        BlockPos pos,
        int energy,
        int capacity,
        boolean powered,
        int windowIn,
        int windowOut,
        List<Frame> frames)
        implements CustomPacketPayload {

    public PowerJunctionSyncPayload(
            BlockPos pos,
            int energy,
            int capacity,
            boolean powered,
            int windowIn,
            int windowOut,
            PowerUsageSampler.Sample[] samples) {
        this(pos, energy, capacity, powered, windowIn, windowOut, toFrames(samples));
    }

    public static final Type<PowerJunctionSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "power_junction_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PowerJunctionSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, PowerJunctionSyncPayload::pos,
                    ByteBufCodecs.VAR_INT, PowerJunctionSyncPayload::energy,
                    ByteBufCodecs.VAR_INT, PowerJunctionSyncPayload::capacity,
                    ByteBufCodecs.BOOL, PowerJunctionSyncPayload::powered,
                    ByteBufCodecs.VAR_INT, PowerJunctionSyncPayload::windowIn,
                    ByteBufCodecs.VAR_INT, PowerJunctionSyncPayload::windowOut,
                    Frame.STREAM_CODEC.apply(ByteBufCodecs.list()), PowerJunctionSyncPayload::frames,
                    PowerJunctionSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PowerJunctionSyncPayload payload,
                              net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> ClientPowerJunctionGui.handle(payload));
    }

    private static List<Frame> toFrames(PowerUsageSampler.Sample[] samples) {
        List<Frame> frames = new ArrayList<>(samples.length);
        for (PowerUsageSampler.Sample sample : samples) {
            frames.add(new Frame(sample.totalIn(), sample.totalOut(), sample.byKind()));
        }
        return frames;
    }

    public record Frame(int totalIn, int totalOut, int[] byKind) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Frame> STREAM_CODEC =
                StreamCodec.of(
                        (buf, frame) -> {
                            buf.writeVarInt(frame.totalIn());
                            buf.writeVarInt(frame.totalOut());
                            buf.writeVarInt(frame.byKind().length);
                            for (int v : frame.byKind()) {
                                buf.writeVarInt(v);
                            }
                        },
                        buf -> {
                            int totalIn = buf.readVarInt();
                            int totalOut = buf.readVarInt();
                            int len = buf.readVarInt();
                            int[] byKind = new int[Math.max(len, PowerSpendKind.VALUES.length)];
                            for (int i = 0; i < len; i++) {
                                int v = buf.readVarInt();
                                if (i < byKind.length) {
                                    byKind[i] = v;
                                }
                            }
                            return new Frame(totalIn, totalOut, byKind);
                        });
    }
}
