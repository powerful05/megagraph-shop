package com.megagraph.shop.api;

import com.megagraph.shop.guide.GuideConversationService;
import com.megagraph.shop.guide.GuideConversationStore;
import com.megagraph.shop.guide.GuideConversationViews;
import com.megagraph.shop.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("guide")
@RequestMapping("/api/shop/v1/guide/conversations")
public class GuideConversationController {
    private final CurrentUser currentUser;
    private final GuideConversationStore store;
    private final GuideConversationService service;

    public GuideConversationController(CurrentUser currentUser, GuideConversationStore store, GuideConversationService service) {
        this.currentUser = currentUser;
        this.store = store;
        this.service = service;
    }

    public record Message(@NotNull UUID requestId, @NotBlank @Size(max = 2000) String content) {}

    @GetMapping
    ResponseEntity<List<GuideConversationViews.Summary>> list(Authentication authentication) {
        return response(store.list(currentUser.requireAccount(authentication).getId()));
    }

    @PostMapping
    ResponseEntity<GuideConversationViews.Detail> create(Authentication authentication) {
        return response(store.create(currentUser.requireAccount(authentication).getId()));
    }

    @GetMapping("/{id}")
    ResponseEntity<GuideConversationViews.Detail> get(Authentication authentication, @PathVariable UUID id) {
        return response(store.get(currentUser.requireAccount(authentication).getId(), id.toString()));
    }

    @PostMapping("/{id}/messages")
    ResponseEntity<GuideConversationViews.Request> send(Authentication authentication, @PathVariable UUID id,
                                                       @Valid @RequestBody Message message) {
        return response(service.send(currentUser.requireAccount(authentication).getId(), id.toString(),
                message.requestId().toString(), message.content()));
    }

    @PostMapping("/{id}/requests/{requestId}/retry")
    ResponseEntity<GuideConversationViews.Request> retry(Authentication authentication, @PathVariable UUID id,
                                                        @PathVariable UUID requestId) {
        return response(service.retry(currentUser.requireAccount(authentication).getId(), id.toString(), requestId.toString()));
    }

    private <T> ResponseEntity<T> response(T value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(value);
    }
}
