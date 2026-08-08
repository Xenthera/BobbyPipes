package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.menu.FuelGeneratorMenu;
import com.bobby.bobbypipes.menu.TwerkGeneratorMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server -> client: live generator readout while its screen is open.
 *
 * <p>One payload serves both generators. The twerk generator simply sends zero for the burn
 * fields; a second packet type to carry two unused ints would be ceremony for its own sake.
 *
 * @param burnTicks      ticks of burn left on the current fuel item, 0 when not burning
 * @param burnTicksTotal how long that item burns in total, for the consumption meter
 */
public record GeneratorSyncPayload(BlockPos pos, int energy, int capacity,
                                   int burnTicks, int burnTicksTotal)
        implements CustomPacketPayload {

    public static final Type<GeneratorSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "generator_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GeneratorSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, GeneratorSyncPayload::pos,
                    ByteBufCodecs.VAR_INT, GeneratorSyncPayload::energy,
                    ByteBufCodecs.VAR_INT, GeneratorSyncPayload::capacity,
                    ByteBufCodecs.VAR_INT, GeneratorSyncPayload::burnTicks,
                    ByteBufCodecs.VAR_INT, GeneratorSyncPayload::burnTicksTotal,
                    GeneratorSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GeneratorSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var menu = Minecraft.getInstance().player == null
                    ? null
                    : Minecraft.getInstance().player.containerMenu;
            if (menu instanceof FuelGeneratorMenu fuel) {
                fuel.applySync(payload);
            } else if (menu instanceof TwerkGeneratorMenu twerk) {
                twerk.applySync(payload);
            }
        });
    }
}
