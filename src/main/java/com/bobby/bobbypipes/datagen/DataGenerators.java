package com.bobby.bobbypipes.datagen;

import com.bobby.bobbypipes.BobbyPipes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Entry point for {@code ./gradlew runData}.
 *
 * <p>Output lands in {@code src/generated/resources}, which {@code build.gradle} already
 * adds to the main resource set. Generated files are committed like any other resource, so
 * a normal build never has to run the generator.
 */
@EventBusSubscriber(modid = BobbyPipes.MOD_ID)
public final class DataGenerators {

    private DataGenerators() {
    }

    @SubscribeEvent
    public static void gatherData(GatherDataEvent.Client event) {
        event.createProvider(PipeModelProvider::new);
    }
}
