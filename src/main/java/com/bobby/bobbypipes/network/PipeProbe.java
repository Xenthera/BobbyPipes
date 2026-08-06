package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.block.PipeBlock;
import com.bobby.bobbypipes.block.ProviderPipeBlock;
import com.bobby.bobbypipes.block.RequestPipeBlock;
import com.bobby.bobbypipes.block.RoutedPipeBlock;
import com.bobby.bobbypipes.block.entity.BasicPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.CraftingPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.PassiveSupplierPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.SatellitePipeBlockEntity;
import com.bobby.bobbypipes.block.entity.StockTargetPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.SupplierPipeBlockEntity;
import com.bobby.bobbypipes.craft.CraftPattern;
import com.bobby.bobbypipes.pipes.SupplierRequests;
import com.bobby.bobbypipes.transit.EnergyShipment;
import com.bobby.bobbypipes.transit.FluidShipment;
import com.bobby.bobbypipes.transit.ItemShipment;
import com.bobby.bobbypipes.transit.Parcel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds human-readable status lines for the pipe a player is probing (Pipe Goggles).
 */
public final class PipeProbe {

    private static final int MAX_LINES = 14;

    private PipeProbe() {
    }

    public static List<String> describe(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (!(block instanceof PipeBlock)) {
            return List.of();
        }

        List<String> lines = new ArrayList<>(MAX_LINES);
        lines.add(state.getBlock().getName().getString());

        PipeNetwork network = PipeNetwork.get(level);
        // World load never place-invalidates existing pipes, so the cache can still be
        // empty on first probe. Rebuild from this pipe when it is missing.
        if (!network.routes().contains(pos)) {
            network.rebuildNow(pos);
        }
        boolean onNetwork = network.routes().contains(pos);
        if (onNetwork) {
            int component = network.routes().topology().componentOf(pos).size();
            int neighbours = network.routes().topology().neighbours(pos).size();
            lines.add("Network: " + component + " nodes, " + neighbours + " link(s)");
        } else {
            lines.add("Network: not connected");
        }

        int parcelsHere = 0;
        for (Parcel<BlockPos, ItemShipment> parcel : network.parcels().parcels()) {
            if (parcel.atNode().equals(pos)) {
                parcelsHere++;
            }
        }
        if (parcelsHere > 0) {
            lines.add("Parcels here: " + parcelsHere);
        }
        appendEnergyAndFluidParcels(network, pos, lines);

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof CraftingPipeBlockEntity crafting) {
            appendCrafting(level, network, pos, crafting, lines);
        } else if (be instanceof BasicPipeBlockEntity basic) {
            appendBasic(basic, lines);
        } else if (be instanceof SatellitePipeBlockEntity satellite) {
            appendSatellite(satellite, lines);
        } else if (be instanceof StockTargetPipeBlockEntity stock) {
            appendStockTarget(level, network, pos, stock, lines);
        } else if (block instanceof ProviderPipeBlock) {
            appendProvider(level, network, pos, lines);
        } else if (block instanceof RequestPipeBlock) {
            lines.add("Request terminal");
        } else if (block instanceof RoutedPipeBlock) {
            lines.add("Routed pipe");
        } else {
            lines.add("Transport tube");
        }

        if (lines.size() > MAX_LINES) {
            return List.copyOf(lines.subList(0, MAX_LINES));
        }
        return List.copyOf(lines);
    }

    /**
     * Energy and fluid parcels sitting on this pipe, with what each is carrying.
     *
     * <p>Worth its own lines rather than folding into the item count above: these draw as
     * one anonymous model, so the amount and tier are otherwise invisible in world, and
     * seeing "3.4M FE T3" go past is the only way to confirm a big provider really is
     * releasing in bulk rather than trickling.
     */
    private static void appendEnergyAndFluidParcels(PipeNetwork network, BlockPos pos,
                                                    List<String> lines) {
        for (Parcel<BlockPos, EnergyShipment> parcel : network.energyParcels().parcels()) {
            if (!parcel.atNode().equals(pos) || lines.size() >= MAX_LINES) {
                continue;
            }
            EnergyShipment shipment = parcel.payload();
            lines.add("Energy: " + compact(shipment.amountFe()) + " FE " + shipment.tier().label());
        }
        for (Parcel<BlockPos, FluidShipment> parcel : network.fluidParcels().parcels()) {
            if (!parcel.atNode().equals(pos) || lines.size() >= MAX_LINES) {
                continue;
            }
            FluidShipment shipment = parcel.payload();
            lines.add("Fluid: " + compact(shipment.amountMb()) + " mB "
                    + fluidName(shipment.resource()) + " " + shipment.tier().label());
        }
    }

    /** 1234567 as "1.2M", so a bulk parcel's amount fits one goggles line. */
    private static String compact(int amount) {
        if (amount >= 1_000_000) {
            return String.format("%.1fM", amount / 1_000_000.0);
        }
        if (amount >= 10_000) {
            return String.format("%.0fk", amount / 1000.0);
        }
        return Integer.toString(amount);
    }

    private static String fluidName(FluidResource fluid) {
        return fluid.isEmpty() ? "empty" : fluid.getHoverName().getString();
    }

    private static void appendCrafting(ServerLevel level, PipeNetwork network, BlockPos pos,
                                       CraftingPipeBlockEntity crafting, List<String> lines) {
        CraftPattern pattern = crafting.pattern();
        if (pattern.isEmpty()) {
            lines.add("Pattern: none");
            return;
        }
        ItemStack out = pattern.primaryOutput();
        lines.add("Output: " + (out.isEmpty() ? "empty" : stackLabel(out)));
        String sat = pattern.satellite();
        lines.add("Satellite: " + (sat.isEmpty() ? "none" : sat));

        List<CraftJobManager.JobReport> reports = network.craftJobs().describe(level, network);
        boolean any = false;
        for (CraftJobManager.JobReport report : reports) {
            if (!report.crafter().equals(pos)) {
                continue;
            }
            any = true;
            lines.add(report.headline());
            for (String detail : report.detail()) {
                if (lines.size() >= MAX_LINES) {
                    return;
                }
                lines.add("  " + detail);
            }
        }
        if (!any) {
            lines.add("Job: idle");
        }
    }

    private static void appendBasic(BasicPipeBlockEntity basic, List<String> lines) {
        lines.add("Default route: " + (basic.isDefaultRoute() ? "yes" : "no"));
        var intake = basic.itemHandler();
        for (int i = 0; i < intake.size(); i++) {
            ItemResource item = intake.getResource(i);
            int amount = intake.getAmountAsInt(i);
            if (!item.isEmpty() && amount > 0) {
                lines.add("Intake: " + amount + "x " + itemName(item));
            }
        }
    }

    private static void appendSatellite(SatellitePipeBlockEntity satellite, List<String> lines) {
        String name = satellite.satelliteName();
        lines.add("Name: " + (name.isEmpty() ? "(unnamed)" : name));
    }

    private static void appendStockTarget(ServerLevel level, PipeNetwork network, BlockPos pos,
                                          StockTargetPipeBlockEntity stock, List<String> lines) {
        boolean active = stock instanceof SupplierPipeBlockEntity;
        boolean passive = stock instanceof PassiveSupplierPipeBlockEntity;
        lines.add(active ? "Mode: request shortfalls" : passive ? "Mode: passive sink" : "Mode: stock target");
        SupplierRequests requests = stock.requests();
        if (requests.isEmpty()) {
            lines.add("Targets: none");
            return;
        }
        for (int i = 0; i < SupplierRequests.SLOT_COUNT; i++) {
            if (lines.size() >= MAX_LINES) {
                return;
            }
            ItemStack ghost = requests.slot(i);
            if (ghost.isEmpty()) {
                continue;
            }
            ItemResource item = ItemResource.of(ghost);
            int target = ghost.getCount();
            int have = InventoryAccess.count(level, pos, item);
            int inbound = network.inboundTo(pos, item);
            int need = Math.max(0, target - have - inbound);
            lines.add(stackLabel(ghost) + ": " + have + "/" + target
                    + " (+" + inbound + " in)" + (need > 0 ? " need " + need : " ok"));
        }
    }

    private static void appendProvider(ServerLevel level, PipeNetwork network, BlockPos pos,
                                       List<String> lines) {
        lines.add("Provider");
        int queued = network.sendQueue().queuedFrom(pos);
        if (queued > 0) {
            lines.add("Queued out: " + queued);
        }
        Map<ItemResource, Integer> stock = ProviderAccess.summarize(level, pos);
        if (stock.isEmpty()) {
            lines.add("Attached stock: empty / none");
            return;
        }
        int shown = 0;
        for (Map.Entry<ItemResource, Integer> entry : stock.entrySet()) {
            if (lines.size() >= MAX_LINES || shown >= 5) {
                int remaining = stock.size() - shown;
                if (remaining > 0 && lines.size() < MAX_LINES) {
                    lines.add("  … +" + remaining + " more type(s)");
                }
                break;
            }
            lines.add("  " + entry.getValue() + "x " + itemName(entry.getKey()));
            shown++;
        }
    }

    private static String stackLabel(ItemStack stack) {
        return stack.getCount() + "x " + stack.getHoverName().getString();
    }

    private static String itemName(ItemResource item) {
        return item.isEmpty() ? "empty" : item.toStack(1).getHoverName().getString();
    }
}
