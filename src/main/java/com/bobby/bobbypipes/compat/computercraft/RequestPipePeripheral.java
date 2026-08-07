package com.bobby.bobbypipes.compat.computercraft;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.network.NetworkSupply;
import com.bobby.bobbypipes.network.PipeNetwork;
import com.bobby.bobbypipes.network.RequestService;
import com.bobby.bobbypipes.request.Demand;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ComputerCraft peripheral for the item Request Pipe (no block entity — pos-based).
 */
public final class RequestPipePeripheral implements IPeripheral {

    private final ServerLevel level;
    private final BlockPos pos;

    public RequestPipePeripheral(ServerLevel level, BlockPos pos) {
        this.level = level;
        this.pos = pos.immutable();
    }

    @Override
    public String getType() {
        return BobbyPipes.MOD_ID + ":request_pipe";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof RequestPipePeripheral p
                && p.level == level
                && p.pos.equals(pos);
    }

    @LuaFunction(mainThread = true)
    public final Map<Integer, Map<String, Object>> getAvailable() throws LuaException {
        ensureRouted();
        List<NetworkSupply.CatalogEntry> catalog = PipeNetwork.get(level).supplyFor(pos).catalog();
        Map<Integer, Map<String, Object>> out = new HashMap<>(catalog.size());
        int i = 1;
        for (NetworkSupply.CatalogEntry entry : catalog) {
            out.put(i++, CcHelpers.itemCatalogEntry(entry.item(), entry.amount(), entry.craftable()));
        }
        return out;
    }

    @LuaFunction(mainThread = true)
    public final int getItemCount(String itemId) throws LuaException {
        ensureRouted();
        ItemResource item = CcHelpers.parseItem(itemId);
        return PipeNetwork.get(level).supplyFor(pos).catalog().stream()
                .filter(e -> e.item().equals(item))
                .mapToInt(NetworkSupply.CatalogEntry::amount)
                .findFirst()
                .orElse(0);
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> request(String itemId, int count) throws LuaException {
        if (count <= 0) {
            throw new LuaException("count must be positive");
        }
        ItemResource item = CcHelpers.parseItem(itemId);
        RequestService.Outcome outcome = RequestService.requestWhatYouCan(level, pos, item, count);
        if (!outcome.hasPipe()) {
            throw new LuaException("No pipe network at this position");
        }
        RequestService.Commitment commitment = outcome.commitment();
        int shipped = commitment == null ? 0 : commitment.shipped();
        Map<String, Object> result = new HashMap<>(3);
        result.put("shipped", shipped);
        result.put("requested", count);
        result.put("complete", shipped >= count);
        return result;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> canRequest(String itemId, int count) throws LuaException {
        if (count <= 0) {
            throw new LuaException("count must be positive");
        }
        ItemResource item = CcHelpers.parseItem(itemId);
        RequestService.Outcome outcome = RequestService.request(level, pos, item, count, false);
        if (!outcome.hasPipe()) {
            throw new LuaException("No pipe network at this position");
        }
        Map<String, Object> result = new HashMap<>(2);
        result.put("satisfiable", outcome.plan().isComplete());
        List<Map<String, Object>> missing = new ArrayList<>();
        for (Demand<ItemResource> shortfall : outcome.plan().missing()) {
            missing.add(CcHelpers.itemEntry(shortfall.item(), shortfall.amount()));
        }
        if (!missing.isEmpty()) {
            result.put("missing", missing);
        }
        return result;
    }

    private void ensureRouted() throws LuaException {
        PipeNetwork network = PipeNetwork.get(level);
        if (!network.isDirty() && network.routes().contains(pos)) {
            return;
        }
        if (!network.rebuildNow(pos).contains(pos)) {
            throw new LuaException("No pipe network at this position");
        }
    }
}
