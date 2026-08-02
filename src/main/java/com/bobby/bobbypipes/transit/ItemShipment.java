package com.bobby.bobbypipes.transit;

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
 */
public record ItemShipment(ItemResource resource, int count, long promiseId) {

    public ItemShipment {
        if (count <= 0) {
            throw new IllegalArgumentException("shipment count must be positive, got " + count);
        }
    }

    @Override
    public String toString() {
        return count + "x " + resource + " (promise " + promiseId + ")";
    }
}
