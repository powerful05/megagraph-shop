package com.megagraph.shop.api;

import com.megagraph.shop.catalog.PublicCatalogService;
import com.megagraph.shop.media.MediaPayload;
import com.megagraph.shop.media.MediaService;
import java.util.List;
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

    public ShopController(PublicCatalogService catalog, MediaService mediaService) {
        this.catalog = catalog;
        this.mediaService = mediaService;
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
    ResponseEntity<Resource> media(@PathVariable Long imageId) {
        MediaPayload media = mediaService.loadPublicMedia(imageId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .contentType(MediaType.parseMediaType(media.contentType()))
                .body(media.resource());
    }
}

