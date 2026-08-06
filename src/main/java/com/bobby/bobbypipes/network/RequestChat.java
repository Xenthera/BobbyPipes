package com.bobby.bobbypipes.network;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Chat lines for request-pipe submissions: what was asked for, how much, and whether it
 * went through. Shared by the item, fluid, and energy request screens so the wording stays
 * consistent.
 */
public final class RequestChat {

    private RequestChat() {
    }

    public static void item(ServerPlayer player, ItemResource item, int requested, int shipped) {
        if (item.isEmpty() || requested <= 0) {
            return;
        }
        MutableComponent line = Component.translatable(
                "chat.bobbypipes.request.item",
                item.toStack(1).getHoverName(),
                requested);
        line.append(Component.literal(" — "));
        line.append(result(shipped, requested));
        player.sendSystemMessage(line);
    }

    public static void fluid(ServerPlayer player, FluidResource fluid, int requestedMb, int shippedMb) {
        if (fluid.isEmpty() || requestedMb <= 0) {
            return;
        }
        MutableComponent line = Component.translatable(
                "chat.bobbypipes.request.fluid",
                requestedMb,
                fluid.getFluidType().getDescription());
        line.append(Component.literal(" — "));
        line.append(result(shippedMb, requestedMb));
        player.sendSystemMessage(line);
    }

    public static void energy(ServerPlayer player, int requestedFe, int shippedFe) {
        if (requestedFe <= 0) {
            return;
        }
        MutableComponent line = Component.translatable("chat.bobbypipes.request.energy", requestedFe);
        line.append(Component.literal(" — "));
        line.append(result(shippedFe, requestedFe));
        player.sendSystemMessage(line);
    }

    private static Component result(int shipped, int requested) {
        if (shipped >= requested) {
            return Component.translatable("chat.bobbypipes.request.ok")
                    .withStyle(ChatFormatting.GREEN);
        }
        if (shipped > 0) {
            return Component.translatable("chat.bobbypipes.request.partial", shipped, requested)
                    .withStyle(ChatFormatting.YELLOW);
        }
        return Component.translatable("chat.bobbypipes.request.fail")
                .withStyle(ChatFormatting.RED);
    }
}
