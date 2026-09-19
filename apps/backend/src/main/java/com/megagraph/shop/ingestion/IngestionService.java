package com.megagraph.shop.ingestion;

import com.megagraph.shop.api.IngestionResponse;
import com.megagraph.shop.api.IngestionStepResponse;
import com.megagraph.shop.catalog.ImageRole;
import com.megagraph.shop.catalog.Product;
import com.megagraph.shop.catalog.ProductImage;
import com.megagraph.shop.catalog.ProductImageRepository;
import com.megagraph.shop.catalog.ProductRepository;
import com.megagraph.shop.media.ImageUploadValidator;
import com.megagraph.shop.media.MediaStorage;
import com.megagraph.shop.media.StoredMedia;
import com.megagraph.shop.security.MerchantActor;
import com.megagraph.shop.shared.NotFoundException;
import com.megagraph.shop.shared.ValidationException;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class IngestionService {

    private final IngestionJobRepository jobs;
    private final IngestionStepRepository steps;
    private final ProductRepository products;
    private final ProductImageRepository images;
    private final ImageUploadValidator imageValidator;
    private final MediaStorage mediaStorage;
    private final DraftGenerator draftGenerator;

    public IngestionService(
            IngestionJobRepository jobs,
            IngestionStepRepository steps,
            ProductRepository products,
            ProductImageRepository images,
            ImageUploadValidator imageValidator,
            MediaStorage mediaStorage,
            DraftGenerator draftGenerator
    ) {
        this.jobs = jobs;
        this.steps = steps;
        this.products = products;
        this.images = images;
        this.imageValidator = imageValidator;
        this.mediaStorage = mediaStorage;
        this.draftGenerator = draftGenerator;
    }

    @Transactional
    public IngestionResponse create(MerchantActor actor, String idempotencyKey, MultipartFile image) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new ValidationException("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key 不能为空且最多 128 个字符");
        }

        return jobs.findByMerchantIdAndIdempotencyKey(actor.merchantId(), idempotencyKey.trim())
                .map(this::toResponse)
                .orElseGet(() -> createNew(actor, idempotencyKey.trim(), image));
    }

    @Transactional(readOnly = true)
    public IngestionResponse get(MerchantActor actor, Long jobId) {
        IngestionJob job = jobs.findByIdAndMerchantId(jobId, actor.merchantId())
                .orElseThrow(() -> new NotFoundException("INGESTION_NOT_FOUND", "入库任务不存在"));
        return toResponse(job);
    }

    private IngestionResponse createNew(MerchantActor actor, String idempotencyKey, MultipartFile image) {
        imageValidator.validate(image);
        String originalFilename = safeOriginalFilename(image.getOriginalFilename());

        IngestionJob job = jobs.save(new IngestionJob(
                actor.merchantId(),
                idempotencyKey,
                originalFilename,
                actor.userId()
        ));
        job.start();

        IngestionStep step = steps.save(new IngestionStep(job.getId(), actor.merchantId(), "MOCK_DRAFT_BUILD"));
        step.start();

        DraftSuggestion suggestion = draftGenerator.generate(originalFilename);
        Product product = products.save(new Product(actor.merchantId(), suggestion.title()));
        product.revise(suggestion.title(), suggestion.subtitle(), null);

        try {
            StoredMedia stored = mediaStorage.store(
                    actor.merchantId(),
                    originalFilename,
                    image.getContentType(),
                    image.getInputStream()
            );
            images.save(new ProductImage(
                    actor.merchantId(),
                    product.getId(),
                    stored.storageKey(),
                    originalFilename,
                    stored.contentType(),
                    stored.byteSize(),
                    ImageRole.MAIN,
                    0
            ));
        } catch (IOException exception) {
            throw new ValidationException("IMAGE_STORE_FAILED", "图片保存失败，请重试");
        }

        step.succeed(suggestion.evidenceJson());
        job.succeed(product.getId());
        jobs.flush();
        return toResponse(job);
    }

    private IngestionResponse toResponse(IngestionJob job) {
        List<IngestionStepResponse> stepResponses = steps.findByJobIdOrderByIdAsc(job.getId()).stream()
                .map(step -> new IngestionStepResponse(
                        step.getStepType(),
                        step.getStatus().name(),
                        step.getAttemptCount()
                ))
                .toList();
        return new IngestionResponse(
                job.getId(),
                job.getProductId(),
                job.getStatus().name(),
                job.getOriginalFilename(),
                job.getCreatedAt(),
                job.getFinishedAt(),
                stepResponses
        );
    }

    private String safeOriginalFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "product-image";
        }
        String normalized = filename.replace('\\', '/');
        String base = normalized.substring(normalized.lastIndexOf('/') + 1);
        return base.length() > 255 ? base.substring(base.length() - 255) : base;
    }
}

