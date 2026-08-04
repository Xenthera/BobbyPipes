package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.menu.RequestMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * A routed pipe that requests items from the network and delivers them into the
 * inventories touching it.
 */
public class RequestPipeBlock extends RoutedPipeBlock {

    public RequestPipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected boolean openPipeScreen(ServerPlayer player, Level level, BlockPos pos) {
        RequestMenus.open(player, pos);
        return true;
    }

    /**
     * The one pipe that opens on a bare right click.
     *
     * <p>It holds no configuration and gets used constantly, so requiring the Wrench every
     * time would be friction for no benefit. The Wrench opens it too.
     */
    @Override
    protected boolean opensWithoutWrench() {
        return true;
    }
}
