package com.bobby.bobbypipes.transit;

import net.minecraft.core.Direction;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/**
 * What a fluid parcel carries: some amount of one fluid, plus the promise it settles.
 *
 * <p>Structurally identical to {@link ItemShipment}, fluid has a real resource identity
 * (unlike energy), so it needs the same match-by-promise-id reasoning items do: two
 * requests for the same fluid to the same place must not be conflated.
 *
 * @param resource  the fluid, including its data components
 * @param amountMb  how many millibuckets, always positive
 * @param promiseId the {@code DeliveryLedger} promise this settles
 * @param entrySide the tank face this fluid was pulled out of, or null when it did not
 *                  come from a real capability
 */
public record FluidShipment(FluidResource resource, int amountMb, long promiseId, Direction entrySide) {

    public FluidShipment {
        if (amountMb <= 0) {
            throw new IllegalArgumentException("shipment amount must be positive, got " + amountMb);
        }
    }

    public FluidShipment(FluidResource resource, int amountMb, long promiseId) {
        this(resource, amountMb, promiseId, null);
    }

    /**
     * How dense this packet is, derived from {@link #amountMb} rather than stored. See
     * {@link ParcelTier}.
     */
    public ParcelTier tier() {
        return ParcelTier.forMb(amountMb);
    }

    @Override
    public String toString() {
        return amountMb + " mB " + resource + " " + tier().label() + " (promise " + promiseId + ")";
    }
}
