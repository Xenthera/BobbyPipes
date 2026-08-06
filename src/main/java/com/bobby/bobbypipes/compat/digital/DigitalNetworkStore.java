package com.bobby.bobbypipes.compat.digital;

import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.Map;

/**
 * A digital storage network (AE2 / Refined Storage) offered as provider stock.
 *
 * <p>Unlike a chest-shaped {@code ResourceHandler}, this is key/amount storage with no
 * slots. Leave-one modes do not apply; provider filters still do at the call site.
 */
public interface DigitalNetworkStore {

    /**
     * Claim key so two providers on the same network do not double-count.
     *
     * <p>Typically a {@link com.bobby.bobbypipes.network.StorageIdentities#refKey} over the
     * grid / network object.
     */
    Object identity();

    /** All item stock on the network, amounts capped at {@link Integer#MAX_VALUE}. */
    Map<ItemResource, Integer> summarize();

    /** How many of {@code item} are extractable (simulate). */
    int count(ItemResource item);

    /**
     * Removes up to {@code amount} of {@code item} from the network.
     *
     * @return how many were actually taken
     */
    int extract(ItemResource item, int amount);
}
