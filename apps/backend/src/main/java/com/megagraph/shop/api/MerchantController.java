package com.megagraph.shop.api;

import com.megagraph.shop.catalog.CategoryRepository;
import com.megagraph.shop.catalog.ProductService;
import com.megagraph.shop.identity.MerchantRole;
import com.megagraph.shop.ingestion.IngestionService;
import com.megagraph.shop.media.MediaPayload;
import com.megagraph.shop.media.MediaService;
import com.megagraph.shop.security.CurrentMerchant;
import com.megagraph.shop.security.MerchantActor;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/merchant/v1")
public class MerchantController {

    private final CurrentMerchant currentMerchant;
    private final IngestionService ingestionService;
    private final ProductService productService;
    private final CategoryRepository categories;
    private final MediaService mediaService;

    public MerchantController(
            CurrentMerchant currentMerchant,
            IngestionService ingestionService,
            ProductService productService,
            CategoryRepository categories,
            MediaService mediaService
    ) {
        this.currentMerchant = currentMerchant;
        this.ingestionService = ingestionService;
        this.productService = productService;
        this.categories = categories;
        this.mediaService = mediaService;
    }

    @GetMapping("/me")
    MerchantMeResponse me(Authentication authentication) {
        MerchantActor actor = currentMerchant.requireActor(authentication);
        return new MerchantMeResponse(actor.userId(), actor.merchantId(), actor.role().name());
    }

    @GetMapping("/categories")
    List<CategoryResponse> categories(Authentication authentication) {
        currentMerchant.requireActor(authentication);
        return categories.findByVisibleTrueOrderByLevelNoAscNameAsc().stream()
                .map(category -> new CategoryResponse(
                        category.getId(),
                        category.getParentId(),
                        category.getName(),
                        category.getLevelNo()
                ))
                .toList();
    }

    @PostMapping(value = "/ingestions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    IngestionResponse createIngestion(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestPart("image") MultipartFile image,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="success") String mockScenario
    ) {
        MerchantActor actor = currentMerchant.requireActor(
                authentication,
                MerchantRole.OWNER,
                MerchantRole.EDITOR
        );
        return ingestionService.create(actor, idempotencyKey, image, mockScenario);
    }

    @GetMapping("/ingestions/{jobId}")
    IngestionResponse ingestion(Authentication authentication, @PathVariable Long jobId) {
        return ingestionService.get(currentMerchant.requireActor(authentication), jobId);
    }

    @GetMapping("/ingestions/{jobId}/artifacts")
    List<IngestionArtifactResponse> ingestionArtifacts(Authentication auth,@PathVariable Long jobId) {
        return ingestionService.artifacts(currentMerchant.requireActor(auth),jobId);
    }
    @GetMapping("/ingestions")
    List<IngestionResponse> ingestions(Authentication auth) { return ingestionService.list(currentMerchant.requireActor(auth)); }
    @PostMapping("/ingestions/{jobId}/retry")
    IngestionResponse retryIngestion(Authentication auth,@PathVariable Long jobId,@Valid @RequestBody VersionRequest request) {
        return ingestionService.retry(currentMerchant.requireActor(auth,MerchantRole.OWNER,MerchantRole.EDITOR),jobId,request.version());
    }
    @PostMapping("/ingestions/{jobId}/cancel")
    IngestionResponse cancelIngestion(Authentication auth,@PathVariable Long jobId,@Valid @RequestBody VersionRequest request) {
        return ingestionService.cancel(currentMerchant.requireActor(auth,MerchantRole.OWNER,MerchantRole.EDITOR),jobId,request.version());
    }
    @GetMapping("/ingestions/{jobId}/media")
    ResponseEntity<Resource> ingestionMedia(Authentication auth,@PathVariable Long jobId) {
        return mediaResponse(ingestionService.inputMedia(currentMerchant.requireActor(auth),jobId),"private, no-store");
    }
    @GetMapping("/products")
    List<ProductResponse> products(Authentication authentication) {
        return productService.list(currentMerchant.requireActor(authentication));
    }

    @GetMapping("/products/{productId}/draft")
    ProductResponse product(Authentication authentication, @PathVariable Long productId) {
        return productService.get(currentMerchant.requireActor(authentication), productId);
    }

    @PatchMapping("/products/{productId}/draft")
    ProductResponse updateDraft(
            Authentication authentication,
            @PathVariable Long productId,
            @Valid @RequestBody ProductDraftUpdateRequest request
    ) {
        MerchantActor actor = currentMerchant.requireActor(
                authentication,
                MerchantRole.OWNER,
                MerchantRole.EDITOR
        );
        return productService.updateDraft(actor, productId, request);
    }

    @PostMapping("/products/{productId}/confirm")
    ProductResponse confirm(
            Authentication authentication,
            @PathVariable Long productId,
            @Valid @RequestBody VersionRequest request
    ) {
        MerchantActor actor = currentMerchant.requireActor(
                authentication,
                MerchantRole.OWNER,
                MerchantRole.EDITOR,
                MerchantRole.PUBLISHER
        );
        return productService.confirm(actor, productId, request.version());
    }

    @PostMapping("/products/{productId}/publish")
    ProductResponse publish(
            Authentication authentication,
            @PathVariable Long productId,
            @Valid @RequestBody VersionRequest request
    ) {
        MerchantActor actor = currentMerchant.requireActor(
                authentication,
                MerchantRole.OWNER,
                MerchantRole.PUBLISHER
        );
        return productService.publish(actor, productId, request.version());
    }

    @PostMapping("/products/{productId}/off-shelf")
    ProductResponse offShelf(
            Authentication authentication,
            @PathVariable Long productId,
            @Valid @RequestBody VersionRequest request
    ) {
        MerchantActor actor = currentMerchant.requireActor(
                authentication,
                MerchantRole.OWNER,
                MerchantRole.PUBLISHER
        );
        return productService.offShelf(actor, productId, request.version());
    }

    @GetMapping("/media/{imageId}")
    ResponseEntity<Resource> merchantMedia(Authentication authentication, @PathVariable Long imageId) {
        MediaPayload media = mediaService.loadMerchantMedia(
                currentMerchant.requireActor(authentication),
                imageId
        );
        return mediaResponse(media, "private, max-age=300");
    }

    private ResponseEntity<Resource> mediaResponse(MediaPayload payload, String cacheControl) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, cacheControl)
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .body(payload.resource());
    }
}

