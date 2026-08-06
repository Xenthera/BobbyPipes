package com.bobby.bobbypipes.compat.ae2;

import com.bobby.bobbypipes.compat.digital.DigitalNetworkStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

import java.util.Optional;

/**
 * Soft gate for AE2 full-network provider access.
 *
 * <p>All AE2 types live in {@link Ae2NetworkAccess} so this class never classloads them
 * when the mod is absent.
 */
public final class Ae2Bridge {

    private static final boolean LOADED = ModList.get().isLoaded("ae2");

    private Ae2Bridge() {
    }

    public static boolean present() {
        return LOADED;
    }

    public static Optional<DigitalNetworkStore> tryAttach(ServerLevel level,
                                                          BlockPos neighbour,
                                                          Direction sideFacingPipe) {
        if (!LOADED) {
            return Optional.empty();
        }
        return Ae2NetworkAccess.tryAttach(level, neighbour, sideFacingPipe);
    }
}
