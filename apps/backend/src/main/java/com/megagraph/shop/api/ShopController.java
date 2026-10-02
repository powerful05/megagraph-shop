package com.megagraph.shop.api;

import com.megagraph.shop.catalog.PublicCatalogService;
import com.megagraph.shop.media.MediaPayload;
import com.megagraph.shop.media.MediaService;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shop/v1")
public class ShopController {

    private final PublicCatalogService catalog;
    private final MediaService mediaService;
    private final Environment environment;

    public ShopController(PublicCatalogService catalog, MediaService mediaService, Environment environment) {
        this.catalog = catalog;
        this.mediaService = mediaService;
        this.environment = environment;
    }

    public record ShopCapabilities(boolean mockPaymentAvailable, boolean guideAvailable,
                                   boolean demoCatalogAvailable, String guideModelName) {
    }

    @GetMapping("/capabilities")
    ShopCapabilities capabilities() {
        boolean guide = environment.acceptsProfiles(Profiles.of("guide"));
        return new ShopCapabilities(environment.acceptsProfiles(Profiles.of("local", "test")), guide,
                guide && environment.acceptsProfiles(Profiles.of("local")),
                guide ? environment.getProperty("spring.ai.openai.chat.options.model", "") : "");
    }

    @GetMapping("/products")
    List<PublicProductResponse> products() {
        return catalog.list();
    }

    @GetMapping("/products/{productId}")
    PublicProductResponse product(@PathVariable Long productId) {
        return catalog.get(productId);
    }

    @GetMapping("/media/{imageId}")
    ResponseEntity<Resource> media(@PathVariable Long imageId, @org.springframework.web.bind.annotation.RequestParam(required = false) Integer width) {
        MediaPayload media = mediaService.loadPublicMedia(imageId, width);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .contentType(MediaType.parseMediaType(media.contentType()))
                .body(media.resource());
    }
}
