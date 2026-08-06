package com.bobby.bobbypipes.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * What a pipe actually carries, and therefore what it will grow an arm toward.
 *
 * <p>A pipe attaches to a neighbour only when that neighbour speaks its medium: an energy
 * pipe next to a chest has nothing to say to it, and an item pipe next to a battery box
 * likewise. Before this existed every pipe checked the item capability, so energy and
 * fluid pipes grew arms toward chests they could not use and stayed armless against the
 * storage they were actually feeding.
 */
public enum PipeMedium {

    ITEM {
        @Override
        boolean presentAt(Level level, BlockPos neighbour, Direction touching) {
            return level.getCapability(Capabilities.Item.BLOCK, neighbour, touching) != null;
        }
    },

    ENERGY {
        @Override
        boolean presentAt(Level level, BlockPos neighbour, Direction touching) {
            return level.getCapability(Capabilities.Energy.BLOCK, neighbour, touching) != null;
        }
    },

    FLUID {
        @Override
        boolean presentAt(Level level, BlockPos neighbour, Direction touching) {
            return level.getCapability(Capabilities.Fluid.BLOCK, neighbour, touching) != null;
        }
    };

    /**
     * Whether {@code neighbour} exposes this medium on the face {@code touching}, which is
     * the face pointing back at the pipe asking.
     */
    abstract boolean presentAt(Level level, BlockPos neighbour, Direction touching);
}
