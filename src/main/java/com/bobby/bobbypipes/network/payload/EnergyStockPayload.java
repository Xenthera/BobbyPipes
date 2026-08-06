package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client: roughly how much FE the network can currently supply toward the open
 * energy request pipe. There is only one kind of energy, so unlike {@code NetworkStockPayload}
 * this is a single number rather than a catalog.
 */
public record EnergyStockPayload(int availableFe) implements CustomPacketPayload {

    public static final Type<EnergyStockPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "energy_stock"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EnergyStockPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, EnergyStockPayload::availableFe,
                    EnergyStockPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
