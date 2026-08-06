package com.bobby.bobbypipes.transit;

import net.minecraft.core.Direction;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * What a parcel carries: some quantity of one item, plus the promise it settles.
 *
 * <p>Carrying the promise id rather than matching on arrival by item and destination
 * matters. Two requests for the same item to the same place would otherwise be
 * indistinguishable, and the wrong one would get closed out.
 *
 * @param resource  the item, including its data components
 * @param count     how many, always positive
 * @param promiseId the {@code DeliveryLedger} promise this settles
 * @param entrySide the container face this item was pulled out of, or null when it came
 *                  from a pipe rather than an inventory. Purely visual: it tells the
 *                  renderer which arm to set off from. Null carries as much meaning as a
 *                  side does, because an item handed over by a drifting item entered
 *                  through pipe, and starting it in some unrelated container's arm makes
 *                  it jump sideways before it moves off.
 */
public record ItemShipment(ItemResource resource, int count, long promiseId,
                           Direction entrySide) {

    public ItemShipment {
        if (count <= 0) {
            throw new IllegalArgumentException("shipment count must be positive, got " + count);
        }
    }

    public ItemShipment(ItemResource resource, int count, long promiseId) {
        this(resource, count, promiseId, null);
    }

    /** Cleared when a shipment re-enters the network at a cross-dim link mouth. */
    public ItemShipment withoutEntrySide() {
        return entrySide == null ? this : new ItemShipment(resource, count, promiseId, null);
    }

    @Override
    public String toString() {
        return count + "x " + resource + " (promise " + promiseId + ")";
    }
}
