package com.megagraph.shop.media;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import org.springframework.core.io.ByteArrayResource;

final class MediaPreview {
    private MediaPreview() {}

    static MediaPayload resize(MediaPayload original, int width) {
        try {
            if (original.resource().contentLength() > 10 * 1024 * 1024) return original;
            try (var stream = original.resource().getInputStream(); var input = ImageIO.createImageInputStream(stream)) {
                if (input == null) return original;
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) return original;
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    int sourceWidth = reader.getWidth(0);
                    int sourceHeight = reader.getHeight(0);
                    if (sourceWidth < 1 || sourceHeight < 1 || (long) sourceWidth * sourceHeight > 32_000_000) return original;
                    double scale = Math.min(1.0, Math.min((double) width / sourceWidth, 2400.0 / sourceHeight));
                    if (scale == 1 && original.contentType().equals("image/jpeg")) return original;
                    int targetWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
                    int targetHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
                    var parameters = reader.getDefaultReadParam();
                    int sampling = Math.max(1, (int) (1.0 / scale));
                    parameters.setSourceSubsampling(sampling, sampling, 0, 0);
                    BufferedImage source = reader.read(0, parameters);
                    boolean transparent = hasTransparency(source);
                    var resized = new BufferedImage(targetWidth, targetHeight,
                            transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                    var graphics = resized.createGraphics();
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
                    } finally { graphics.dispose(); }
                    try (var bytes = new ByteArrayOutputStream()) {
                        String format = transparent ? "png" : "jpeg";
                        var writer = ImageIO.getImageWritersByFormatName(format).next();
                        try (var output = ImageIO.createImageOutputStream(bytes)) {
                            writer.setOutput(output);
                            var options = writer.getDefaultWriteParam();
                            if (!transparent) {
                                options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                                options.setCompressionQuality(0.84f);
                            }
                            writer.write(null, new javax.imageio.IIOImage(resized, null, null), options);
                        } finally { writer.dispose(); }
                        byte[] encoded = bytes.toByteArray();
                        if (encoded.length >= original.resource().contentLength()) return original;
                        return new MediaPayload(new ByteArrayResource(encoded), "image/" + format);
                    }
                } finally { reader.dispose(); }
            }
        } catch (IOException | RuntimeException ignored) {
            return original;
        }
    }

    private static boolean hasTransparency(BufferedImage image) {
        var alpha = image.getAlphaRaster();
        if (alpha == null) return false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (alpha.getSample(x, y, 0) < 255) return true;
            }
        }
        return false;
    }
}
