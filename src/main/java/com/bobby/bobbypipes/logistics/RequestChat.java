package com.bobby.bobbypipes.logistics;

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
        item(player, item, requested, shipped, "", "");
    }

    public static void item(ServerPlayer player, ItemResource item, int requested, int shipped,
                            String failKey, String failDetail) {
        if (item.isEmpty() || requested <= 0) {
            return;
        }
        MutableComponent line = Component.translatable(
                "chat.bobbypipes.request.item",
                item.toStack(1).getHoverName(),
                requested);
        line.append(Component.literal(" - "));
        line.append(result(shipped, requested, failKey, failDetail));
        player.sendSystemMessage(line);
    }

    public static void fluid(ServerPlayer player, FluidResource fluid, int requestedMb, int shippedMb) {
        fluid(player, fluid, requestedMb, shippedMb, "");
    }

    public static void fluid(ServerPlayer player, FluidResource fluid, int requestedMb,
                             int shippedMb, String failKey) {
        if (fluid.isEmpty() || requestedMb <= 0) {
            return;
        }
        MutableComponent line = Component.translatable(
                "chat.bobbypipes.request.fluid",
                requestedMb,
                fluid.getFluidType().getDescription());
        line.append(Component.literal(" - "));
        line.append(result(shippedMb, requestedMb, failKey, ""));
        player.sendSystemMessage(line);
    }

    public static void energy(ServerPlayer player, int requestedFe, int shippedFe) {
        energy(player, requestedFe, shippedFe, "");
    }

    public static void energy(ServerPlayer player, int requestedFe, int shippedFe, String failKey) {
        if (requestedFe <= 0) {
            return;
        }
        MutableComponent line = Component.translatable("chat.bobbypipes.request.energy", requestedFe);
        line.append(Component.literal(" - "));
        line.append(result(shippedFe, requestedFe, failKey, ""));
        player.sendSystemMessage(line);
    }

    private static Component result(int shipped, int requested, String failKey, String failDetail) {
        if (shipped >= requested) {
            return Component.translatable("chat.bobbypipes.request.ok")
                    .withStyle(ChatFormatting.GREEN);
        }
        if (shipped > 0) {
            return Component.translatable("chat.bobbypipes.request.partial", shipped, requested)
                    .withStyle(ChatFormatting.YELLOW);
        }
        String key = failKey == null || failKey.isEmpty()
                ? "chat.bobbypipes.request.fail"
                : failKey;
        MutableComponent fail = Component.translatable(key).withStyle(ChatFormatting.RED);
        if (failDetail != null && !failDetail.isEmpty()) {
            fail.append(Component.literal(" (" + failDetail + ")")
                    .withStyle(ChatFormatting.GRAY));
        }
        return fail;
    }
}
