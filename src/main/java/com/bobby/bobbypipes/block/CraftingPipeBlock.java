package com.bobby.bobbypipes.block;

import com.bobby.bobbypipes.block.entity.CraftingPipeBlockEntity;
import com.bobby.bobbypipes.craft.CraftPattern;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Routed pipe that hosts a {@link com.bobby.bobbypipes.craft.CraftPattern} and performs
 * autocrafts into its adjacent inventory.
 */
public class CraftingPipeBlock extends RoutedPipeBlock implements EntityBlock {

    public CraftingPipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CraftingPipeBlockEntity(pos, state);
    }

    @Override
    protected boolean openPipeScreen(ServerPlayer player, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof CraftingPipeBlockEntity pipe)) {
            return false;
        }
        player.openMenu(pipe, buf -> {
            buf.writeBlockPos(pos);
            CraftPattern.STREAM_CODEC.encode(buf, pipe.pattern());
        });
        return true;
    }
}
