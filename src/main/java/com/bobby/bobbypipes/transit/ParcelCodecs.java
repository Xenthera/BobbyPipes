package com.bobby.bobbypipes.transit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.Optional;

/**
 * Codecs for parcels and their payloads, so items in transit survive a world reload.
 *
 * <p>These live here rather than on the records themselves because the records are the
 * transport model and have no other reason to know about serialization.
 */
public final class ParcelCodecs {

    /**
     * {@code entrySide} is genuinely optional - a parcel handed over by a drifting item came
     * from a pipe, not a container face, and null is the meaningful value for that.
     */
    private static final MapCodec<Direction> ENTRY_SIDE =
            Direction.CODEC.optionalFieldOf("entry_side")
                    .xmap(side -> side.orElse(null), Optional::ofNullable);

    public static final Codec<ItemShipment> ITEM_SHIPMENT =
            RecordCodecBuilder.create(instance -> instance.group(
                    ItemResource.CODEC.fieldOf("resource").forGetter(ItemShipment::resource),
                    Codec.INT.fieldOf("count").forGetter(ItemShipment::count),
                    Codec.LONG.fieldOf("promise").forGetter(ItemShipment::promiseId),
                    ENTRY_SIDE.forGetter(ItemShipment::entrySide)
            ).apply(instance, ItemShipment::new));

    public static final Codec<EnergyShipment> ENERGY_SHIPMENT =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.fieldOf("amount_fe").forGetter(EnergyShipment::amountFe),
                    Codec.LONG.fieldOf("promise").forGetter(EnergyShipment::promiseId),
                    ENTRY_SIDE.forGetter(EnergyShipment::entrySide)
            ).apply(instance, EnergyShipment::new));

    public static final Codec<FluidShipment> FLUID_SHIPMENT =
            RecordCodecBuilder.create(instance -> instance.group(
                    FluidResource.CODEC.fieldOf("resource").forGetter(FluidShipment::resource),
                    Codec.INT.fieldOf("amount_mb").forGetter(FluidShipment::amountMb),
                    Codec.LONG.fieldOf("promise").forGetter(FluidShipment::promiseId),
                    ENTRY_SIDE.forGetter(FluidShipment::entrySide)
            ).apply(instance, FluidShipment::new));

    private ParcelCodecs() {
    }

    /**
     * A parcel of {@code payload}, keyed on {@link BlockPos} nodes.
     *
     * <p>{@code nextHop} is written but the loaded value is not trusted: {@link #STALE_REVISION}
     * is stored in its place so {@code ParcelTracker} re-solves the hop against the routing
     * snapshot the world rebuilds on load. Keeping the saved revision would leave a parcel
     * following a routing table that no longer exists.
     */
    public static <P> Codec<Parcel<BlockPos, P>> parcel(Codec<P> payload) {
        return RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.fieldOf("id").forGetter(Parcel::id),
                payload.fieldOf("payload").forGetter(Parcel::payload),
                BlockPos.CODEC.fieldOf("origin").forGetter(Parcel::origin),
                BlockPos.CODEC.fieldOf("destination").forGetter(Parcel::destination),
                BlockPos.CODEC.fieldOf("at").forGetter(Parcel::atNode),
                BlockPos.CODEC.optionalFieldOf("next_hop").forGetter(
                        p -> Optional.ofNullable(p.nextHop())),
                Codec.INT.fieldOf("ticks_into_hop").forGetter(Parcel::ticksIntoHop),
                Codec.INT.fieldOf("ticks_for_hop").forGetter(Parcel::ticksForHop)
        ).apply(instance, (id, load, origin, dest, at, next, into, forHop) ->
                new Parcel<>(id, load, origin, dest, at, next.orElse(null),
                        into, forHop, STALE_REVISION)));
    }

    /**
     * Revision written to every loaded parcel.
     *
     * <p>Any value the freshly solved routing snapshot cannot match works; the minimum is
     * chosen so it can never coincide with a real revision.
     */
    public static final long STALE_REVISION = Long.MIN_VALUE;
}
