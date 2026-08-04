package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.CraftingPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.PatternTableBlockEntity;
import com.bobby.bobbypipes.craft.CraftPattern;
import com.bobby.bobbypipes.menu.CraftingPipeMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SetCraftPatternPayload(BlockPos pos, Target target, CraftPattern pattern)
        implements CustomPacketPayload {

    public enum Target {
        TABLE,
        PIPE
    }

    public static final Type<SetCraftPatternPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "set_craft_pattern"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCraftPatternPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                        buf.writeUtf(payload.target().name());
                        CraftPattern.STREAM_CODEC.encode(buf, payload.pattern());
                    },
                    buf -> new SetCraftPatternPayload(
                            BlockPos.STREAM_CODEC.decode(buf),
                            Target.valueOf(buf.readUtf()),
                            CraftPattern.STREAM_CODEC.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetCraftPatternPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!player.isWithinBlockInteractionRange(payload.pos(), 4.0)) {
                return;
            }
            if (payload.target() == Target.TABLE
                    && player.level().getBlockEntity(payload.pos()) instanceof PatternTableBlockEntity table) {
                table.setPattern(payload.pattern());
                PacketDistributor.sendToPlayer(
                        player, new PatternTableSyncPayload(payload.pos(), table.pattern()));
            } else if (payload.target() == Target.PIPE
                    && player.level().getBlockEntity(payload.pos()) instanceof CraftingPipeBlockEntity pipe) {
                pipe.setPattern(payload.pattern());
                if (player.containerMenu instanceof CraftingPipeMenu menu) {
                    menu.setPatternLocal(pipe.pattern());
                }
            }
        });
    }
}
