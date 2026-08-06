package com.bobby.bobbypipes.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bakes one pipe texture per entry from a shared white base, a tint, and an ordered stack
 * of overlays composited on top, all at atlas-stitch time.
 *
 * <p>This is deliberately not GPU tinting and not a runtime overlay in the model: every
 * output is a single flattened image, computed once when the atlas is built, and referenced
 * by block models exactly like any other static texture. Adding a new pipe colour or a new
 * overlay layer (a power-state indicator, say) never needs a new authored PNG per pipe, only
 * a new entry here pointing at the same handful of shared source images.
 *
 * <p>Composite order per entry: {@code base} tinted by {@code tint}, then each of
 * {@code overlays} alpha-blended on top in list order. A resource-kind edge (energy/fluid)
 * belongs earlier in that list than a connection indicator (direct/indirect), so it reads
 * as a strip under the mark rather than over it.
 */
public record PipeCompositeSource(List<Entry> entries) implements SpriteSource {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final MapCodec<PipeCompositeSource> MAP_CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(Entry.CODEC.listOf().fieldOf("entries").forGetter(PipeCompositeSource::entries))
                    .apply(i, PipeCompositeSource::new));

    /**
     * One baked output.
     *
     * @param output   the texture id block/item models already reference
     * @param base     the shared white/grey source image to tint
     * @param tint     multiplied onto {@code base}'s RGB, its own alpha left untouched
     * @param overlays alpha-blended on top of the tinted base, in order
     */
    public record Entry(Identifier output, Identifier base, int tint, List<Identifier> overlays) {

        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(
                i -> i.group(
                                Identifier.CODEC.fieldOf("output").forGetter(Entry::output),
                                Identifier.CODEC.fieldOf("base").forGetter(Entry::base),
                                Codec.INT.fieldOf("tint").forGetter(Entry::tint),
                                Identifier.CODEC.listOf().optionalFieldOf("overlays", List.of())
                                        .forGetter(Entry::overlays))
                        .apply(i, Entry::new));
    }

    @Override
    public void run(ResourceManager resourceManager, SpriteSource.Output output) {
        // Loaded once per run() even though several entries usually share the same base or
        // overlay image (every pipe colour reuses the same arm base, for instance).
        Map<Identifier, NativeImage> cache = new HashMap<>();
        for (Entry entry : entries) {
            output.add(entry.output(), loader -> bake(resourceManager, entry, cache));
        }
    }

    private static @Nullable SpriteContents bake(ResourceManager resourceManager, Entry entry,
                                                  Map<Identifier, NativeImage> cache) {
        NativeImage base;
        try {
            base = load(resourceManager, entry.base(), cache);
        } catch (IOException e) {
            LOGGER.error("Unable to load pipe composite base {} for {}", entry.base(), entry.output(), e);
            return null;
        }

        NativeImage composed = new NativeImage(base.getWidth(), base.getHeight(), false);
        int tintRgb = ARGB.color(255, ARGB.red(entry.tint()), ARGB.green(entry.tint()), ARGB.blue(entry.tint()));
        for (int y = 0; y < base.getHeight(); y++) {
            for (int x = 0; x < base.getWidth(); x++) {
                composed.setPixel(x, y, ARGB.multiply(base.getPixel(x, y), tintRgb));
            }
        }

        for (Identifier overlayId : entry.overlays()) {
            NativeImage overlay;
            try {
                overlay = load(resourceManager, overlayId, cache);
            } catch (IOException e) {
                LOGGER.error("Unable to load pipe composite overlay {} for {}", overlayId, entry.output(), e);
                composed.close();
                return null;
            }
            int w = Math.min(composed.getWidth(), overlay.getWidth());
            int h = Math.min(composed.getHeight(), overlay.getHeight());
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    composed.setPixel(x, y, ARGB.alphaBlend(composed.getPixel(x, y), overlay.getPixel(x, y)));
                }
            }
        }

        return new SpriteContents(entry.output(), new FrameSize(composed.getWidth(), composed.getHeight()), composed);
    }

    private static NativeImage load(ResourceManager resourceManager, Identifier id,
                                    Map<Identifier, NativeImage> cache) throws IOException {
        NativeImage cached = cache.get(id);
        if (cached != null) {
            return cached;
        }
        Identifier textureId = TEXTURE_ID_CONVERTER.idToFile(id);
        Optional<Resource> resource = resourceManager.getResource(textureId);
        if (resource.isEmpty()) {
            throw new IOException("No such texture: " + textureId);
        }
        NativeImage image;
        try (InputStream stream = resource.get().open()) {
            image = NativeImage.read(stream);
        }
        cache.put(id, image);
        return image;
    }

    @Override
    public MapCodec<PipeCompositeSource> codec() {
        return MAP_CODEC;
    }
}
