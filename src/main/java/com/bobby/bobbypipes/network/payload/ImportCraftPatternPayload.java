package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.CraftingPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.PatternTableBlockEntity;
import com.bobby.bobbypipes.menu.CraftingPipeMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ImportCraftPatternPayload(BlockPos pipePos) implements CustomPacketPayload {

    public static final Type<ImportCraftPatternPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "import_craft_pattern"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ImportCraftPatternPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ImportCraftPatternPayload::pipePos,
                    ImportCraftPatternPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ImportCraftPatternPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!player.isWithinBlockInteractionRange(payload.pipePos(), 4.0)) {
                return;
            }
            if (!(player.level().getBlockEntity(payload.pipePos()) instanceof CraftingPipeBlockEntity pipe)) {
                return;
            }
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = payload.pipePos().relative(direction);
                if (!(player.level().getBlockEntity(neighbour) instanceof PatternTableBlockEntity table)) {
                    continue;
                }
                if (!table.pattern().hasInputs()) {
                    continue;
                }
                // Re-resolve recipe on the table, then copy onto the pipe.
                table.setPattern(table.pattern());
                pipe.setPattern(table.pattern().withSatellite(pipe.pattern().satellite()));
                if (player.containerMenu instanceof CraftingPipeMenu menu
                        && menu.pos().equals(payload.pipePos())) {
                    menu.setPatternLocal(pipe.pattern());
                }
                PacketDistributor.sendToPlayer(
                        player, new CraftingPipeSyncPayload(payload.pipePos(), pipe.pattern()));
                return;
            }
        });
    }
}
