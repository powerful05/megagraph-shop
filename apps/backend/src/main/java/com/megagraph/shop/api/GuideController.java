package com.megagraph.shop.api;

import com.megagraph.shop.guide.GuideAgent;
import com.megagraph.shop.guide.GuideChatResult;
import com.megagraph.shop.guide.GuideModel;
import com.megagraph.shop.shared.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@Profile("guide")
public class GuideController {
    private static final Logger LOG = LoggerFactory.getLogger(GuideController.class);
    private final GuideAgent agent;
    private final boolean demoCatalogAvailable;
    private final Semaphore concurrentRequests = new Semaphore(2);

    public GuideController(GuideAgent agent, Environment environment) {
        this.agent = agent;
        this.demoCatalogAvailable = environment.acceptsProfiles(Profiles.of("local"));
    }

    public record Turn(@NotBlank @Pattern(regexp = "user|assistant") String role,
                       @NotBlank @Size(max = 2000) String content) {
    }

    public record ChatRequest(@NotEmpty @Size(max = 19) List<@NotNull @Valid Turn> messages) {
    }

    @PostMapping("/api/shop/v1/guide/messages")
    ResponseEntity<GuideChatResult> chat(@Valid @RequestBody ChatRequest request) {
        if (!concurrentRequests.tryAcquire()) {
            throw new DomainException(HttpStatus.TOO_MANY_REQUESTS, "GUIDE_BUSY", "导购正在处理其他请求，请稍后重试");
        }
        String requestId = java.util.UUID.randomUUID().toString();
        long started = System.nanoTime();
        org.slf4j.MDC.put("guideRequestId", requestId);
        try {
            GuideChatResult result = agent.chat(request.messages().stream()
                    .map(turn -> new GuideModel.Turn(turn.role(), turn.content())).toList(), demoCatalogAvailable);
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
        } catch (DomainException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOG.warn("Guide request failed: {}", exception.getClass().getSimpleName());
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "GUIDE_MODEL_FAILED",
                    "模型暂时无法回复，请稍后重试");
        } finally {
            LOG.info("Guide request requestId={}, turns={}, milliseconds={}", requestId, request.messages().size(),
                    (System.nanoTime() - started) / 1_000_000);
            org.slf4j.MDC.remove("guideRequestId");
            concurrentRequests.release();
        }
    }
}
