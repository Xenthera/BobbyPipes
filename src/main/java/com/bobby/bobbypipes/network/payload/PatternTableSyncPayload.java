package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.craft.CraftPattern;
import com.bobby.bobbypipes.menu.PatternTableMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record PatternTableSyncPayload(BlockPos pos, CraftPattern pattern) implements CustomPacketPayload {

    public static final Type<PatternTableSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "pattern_table_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PatternTableSyncPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                        CraftPattern.STREAM_CODEC.encode(buf, payload.pattern());
                    },
                    buf -> new PatternTableSyncPayload(
                            BlockPos.STREAM_CODEC.decode(buf),
                            CraftPattern.STREAM_CODEC.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PatternTableSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player != null
                    && player.containerMenu instanceof PatternTableMenu menu
                    && menu.pos().equals(payload.pos())) {
                menu.setPatternLocal(payload.pattern());
            }
        });
    }
}
