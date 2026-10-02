package com.megagraph.shop.api;

import com.megagraph.shop.guide.GuideRequestExecutor;
import com.megagraph.shop.guide.GuideChatResult;
import com.megagraph.shop.guide.GuideModel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GuideController {
    private final GuideRequestExecutor executor;

    public GuideController(GuideRequestExecutor executor) {
        this.executor = executor;
    }

    public record Turn(@NotBlank @Pattern(regexp = "user|assistant") String role,
                       @NotBlank @Size(max = 2000) String content,
                       @Size(max = 3) List<@NotNull @Positive Long> displayedProductIds) {
    }

    public record ChatRequest(@NotEmpty @Size(max = 19) List<@NotNull @Valid Turn> messages) {
    }

    @PostMapping("/api/shop/v1/guide/messages")
    ResponseEntity<GuideChatResult> chat(@Valid @RequestBody ChatRequest request) {
        var result = executor.execute(java.util.UUID.randomUUID().toString(), request.messages().stream()
                .map(turn -> new GuideModel.Turn(turn.role(), turn.content(), turn.displayedProductIds())).toList());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
    }
}
