package com.bobby.bobbypipes.compat.digital;

import com.bobby.bobbypipes.compat.ae2.Ae2Bridge;
import com.bobby.bobbypipes.compat.rs.RsBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/**
 * Soft entry point for digital-network provider attachment (AE2 / Refined Storage).
 *
 * <p>Tried in order: AE2 Interface (block or cable part), then RS Interface. Absent mods
 * short-circuit without loading their classes.
 */
public final class DigitalNetworks {

    private DigitalNetworks() {
    }

    /**
     * Full-network store reachable through the neighbour block on {@code sideFacingPipe}
     * (the face of the neighbour that touches the pipe).
     */
    public static Optional<DigitalNetworkStore> tryAttach(ServerLevel level,
                                                          BlockPos neighbour,
                                                          Direction sideFacingPipe) {
        Optional<DigitalNetworkStore> ae2 = Ae2Bridge.tryAttach(level, neighbour, sideFacingPipe);
        if (ae2.isPresent()) {
            return ae2;
        }
        return RsBridge.tryAttach(level, neighbour, sideFacingPipe);
    }
}
