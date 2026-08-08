package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.entity.FuelGeneratorBlockEntity;
import com.bobby.bobbypipes.block.entity.GeneratorBlockEntity;
import com.bobby.bobbypipes.block.entity.TwerkGeneratorBlockEntity;
import com.bobby.bobbypipes.network.payload.GeneratorSyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Opens generator screens and keeps them fed while they stay open.
 *
 * <p>Both readouts change every tick while running, and neither is worth a packet that often,
 * so the generators sync on a short interval instead.
 */
public final class GeneratorMenus {

    /** Ticks between readout pushes. Four a second is smooth enough for a bar. */
    public static final int SYNC_INTERVAL_TICKS = 5;

    private GeneratorMenus() {
    }

    public static void openFuel(ServerPlayer player, BlockPos pos, FuelGeneratorBlockEntity be) {
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, opener) -> new FuelGeneratorMenu(id, inventory, be),
                be.getDisplayName()), buf -> {
            buf.writeBlockPos(pos);
            buf.writeVarInt(be.energy());
            buf.writeVarInt(be.capacity());
        });
    }

    public static void openTwerk(ServerPlayer player, BlockPos pos, TwerkGeneratorBlockEntity be) {
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, opener) -> new TwerkGeneratorMenu(id, inventory, be),
                be.getDisplayName()), buf -> {
            buf.writeBlockPos(pos);
            buf.writeVarInt(be.energy());
            buf.writeVarInt(be.capacity());
        });
    }

    /**
     * Pushes a readout to every player watching {@code pos}.
     *
     * <p>Called from the generator's own tick, so a generator nobody is looking at costs
     * nothing beyond the menu-type check.
     */
    public static void sync(ServerLevel level, BlockPos pos, GeneratorBlockEntity generator) {
        int burnTicks = 0;
        int burnTotal = 0;
        if (generator instanceof FuelGeneratorBlockEntity fuel) {
            burnTicks = fuel.burnTicks();
            burnTotal = fuel.burnTicksTotal();
        }
        GeneratorSyncPayload payload = new GeneratorSyncPayload(
                pos.immutable(), generator.energy(), generator.capacity(), burnTicks, burnTotal);
        for (ServerPlayer player : level.players()) {
            AbstractContainerMenu menu = player.containerMenu;
            boolean watching = (menu instanceof FuelGeneratorMenu fuelMenu
                        && fuelMenu.pos().equals(pos))
                    || (menu instanceof TwerkGeneratorMenu twerkMenu
                        && twerkMenu.pos().equals(pos));
            if (watching) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }
}
