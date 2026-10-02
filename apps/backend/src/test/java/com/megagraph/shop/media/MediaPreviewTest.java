package com.megagraph.shop.media;

import static org.assertj.core.api.Assertions.assertThat;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class MediaPreviewTest {
    private MediaPayload image(boolean transparent) throws Exception {
        var image = new BufferedImage(1600, 1200, transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var random = new java.util.Random(7);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, (transparent && x < 100 ? 0x44000000 : 0xff000000) | random.nextInt(0x1000000));
            }
        }
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new MediaPayload(new ByteArrayResource(bytes.toByteArray()), "image/png");
    }

    @Test
    void opaquePreviewKeepsAspectRatioWithBoundedSizeAndDoesNotRewriteOriginal() throws Exception {
        var original = image(false);
        byte[] before = original.resource().getContentAsByteArray();
        var result = MediaPreview.resize(original, 640);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        var decoded = ImageIO.read(result.resource().getInputStream());
        assertThat(decoded.getWidth()).isEqualTo(640);
        assertThat(decoded.getHeight()).isEqualTo(480);
        assertThat(result.resource().contentLength()).isLessThan(original.resource().contentLength() / 4);
        assertThat(original.resource().getContentAsByteArray()).isEqualTo(before);
    }

    @Test
    void transparencyIsPreservedInsteadOfAddingAnOpaqueBackground() throws Exception {
        var result = MediaPreview.resize(image(true), 320);
        assertThat(result.contentType()).isEqualTo("image/png");
        var decoded = ImageIO.read(result.resource().getInputStream());
        assertThat(decoded.getWidth()).isEqualTo(320);
        assertThat(decoded.getColorModel().hasAlpha()).isTrue();
        assertThat((decoded.getRGB(0, 0) >>> 24) & 0xff).isLessThan(255);
    }

    @Test
    void unreadableImageFallsBackToOriginalWithoutBlockingTheResource() {
        var original = new MediaPayload(new ByteArrayResource(new byte[] { 1, 2, 3 }), "image/webp");
        assertThat(MediaPreview.resize(original, 640)).isSameAs(original);
    }
}
