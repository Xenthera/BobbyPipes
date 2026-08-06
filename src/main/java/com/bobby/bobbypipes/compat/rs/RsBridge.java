package com.bobby.bobbypipes.compat.rs;

import com.bobby.bobbypipes.compat.digital.DigitalNetworkStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

import java.util.Optional;

/**
 * Soft gate for Refined Storage full-network provider access.
 *
 * <p>All RS types live in {@link RsNetworkAccess} so this class never classloads them when
 * the mod is absent.
 */
public final class RsBridge {

    private static final boolean LOADED = ModList.get().isLoaded("refinedstorage");

    private RsBridge() {
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
        return RsNetworkAccess.tryAttach(level, neighbour, sideFacingPipe);
    }
}
