package com.megagraph.shop.guide;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.payment.CommerceOutboxWriter;
import com.megagraph.shop.shared.DomainException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("guide")
public class GuideConversationStore {
    private final GuideConversationRepository conversations;
    private final GuideTurnRequestRepository requests;
    private final CommerceOutboxWriter outbox;
    private final ObjectMapper mapper;
    private final String modelVersion;

    public GuideConversationStore(GuideConversationRepository conversations, GuideTurnRequestRepository requests,
                                  CommerceOutboxWriter outbox, ObjectMapper mapper,
                                  @Value("${spring.ai.openai.chat.options.model:disabled}") String modelVersion) {
        this.conversations = conversations;
        this.requests = requests;
        this.outbox = outbox;
        this.mapper = mapper;
        this.modelVersion = modelVersion;
    }

    @Transactional(readOnly = true)
    public List<GuideConversationViews.Summary> list(Long owner) {
        return conversations.findTop50ByOwnerUserIdOrderByUpdatedAtDescIdDesc(owner).stream().map(this::summary).toList();
    }

    @Transactional
    public GuideConversationViews.Detail create(Long owner) {
        var conversation = conversations.save(new GuideConversation(owner));
        event(conversation, null, "GUIDE_CONVERSATION_CREATED");
        return detail(conversation);
    }

    @Transactional
    public GuideConversationViews.Detail get(Long owner, String id) {
        var conversation = owned(owner, id);
        expire(conversation, Instant.now());
        return detail(conversation);
    }

    @Transactional
    public GuideConversationViews.Work begin(Long owner, String id, String requestId, String content) {
        var conversation = owned(owner, id);
        Instant now = now();
        expire(conversation, now);
        var existing = requests.findByConversationIdAndRequestId(conversation.id(), requestId);
        if (existing.isPresent()) {
            if (!existing.get().content().equals(content)) {
                throw conflict("GUIDE_REQUEST_CONFLICT", "同一请求标识不能用于不同内容");
            }
            return new GuideConversationViews.Work(view(conversation, existing.get()), false, List.of());
        }
        requireNoPending(conversation);
        var request = requests.save(new GuideTurnRequest(requestId, conversation, content, modelVersion, now));
        event(conversation, request, "GUIDE_REQUEST_STARTED");
        return new GuideConversationViews.Work(view(conversation, request), true, history(conversation, request));
    }

    @Transactional
    public GuideConversationViews.Work retry(Long owner, String id, String requestId) {
        var conversation = owned(owner, id);
        Instant now = now();
        expire(conversation, now);
        var request = request(conversation, requestId);
        if (!request.status().equals("FAILED")) {
            return new GuideConversationViews.Work(view(conversation, request), false, List.of());
        }
        if (!request.sequence().equals(conversation.sequence())) {
            throw conflict("GUIDE_RETRY_SUPERSEDED", "后续已有新消息，请在当前对话继续描述需求");
        }
        requireNoPending(conversation);
        request.start(now);
        conversation.touch(now);
        event(conversation, request, "GUIDE_REQUEST_RETRIED");
        return new GuideConversationViews.Work(view(conversation, request), true, history(conversation, request));
    }

    @Transactional
    public GuideConversationViews.Request finish(Long owner, String id, String requestId, int attempt,
                                                 GuideChatResult result, DomainException failure) {
        var conversation = owned(owner, id);
        var request = request(conversation, requestId);
        Instant now = now();
        expire(conversation, now);
        // The attempt fence prevents an expired upstream call from overwriting a later retry.
        if (!request.status().equals("PENDING") || request.attempt() != attempt) return view(conversation, request);
        if (failure != null) {
            String code = switch (failure.getCode()) {
                case "GUIDE_BUSY", "GUIDE_DISABLED", "GUIDE_INPUT_INVALID", "GUIDE_MODEL_NOT_CONFIGURED" -> failure.getCode();
                default -> "GUIDE_MODEL_FAILED";
            };
            request.fail(code, code.equals("GUIDE_BUSY") ? "导购正在处理其他请求，请稍后重试" : "模型暂时无法回复，请稍后重试", now);
        } else {
            try {
                request.complete(mapper.writeValueAsString(result), now);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Cannot save validated guide result", exception);
            }
        }
        conversation.touch(now);
        event(conversation, request, "GUIDE_REQUEST_" + request.status());
        return view(conversation, request);
    }

    private void expire(GuideConversation conversation, Instant now) {
        for (var request : requests.findByConversationIdAndStatus(conversation.id(), "PENDING")) {
            if (request.expired(now)) {
                request.fail("GUIDE_WAIT_EXPIRED", "请求处理期限已过，请显式重试", now);
                conversation.touch(now);
                event(conversation, request, "GUIDE_REQUEST_FAILED");
            }
        }
    }

    private List<GuideModel.Turn> history(GuideConversation conversation, GuideTurnRequest current) {
        var turns = new ArrayList<GuideModel.Turn>();
        turns.add(new GuideModel.Turn("user", current.content()));
        int characters = current.content().length();
        for (var previous : requests.findTop9ByConversationIdAndStatusOrderByTurnSequenceDesc(conversation.id(), "COMPLETED")) {
            var result = result(previous);
            String text = (result.answer() + "\n" + result.advice()).trim();
            if (text.length() > 2000) text = text.substring(0, 2000);
            if (text.isBlank() || characters + previous.content().length() + text.length() > 12000) break;
            turns.addFirst(new GuideModel.Turn("assistant", text, result.products().stream().map(p -> p.id()).toList()));
            turns.addFirst(new GuideModel.Turn("user", previous.content()));
            characters += previous.content().length() + text.length();
        }
        return List.copyOf(turns);
    }

    private void requireNoPending(GuideConversation conversation) {
        if (!requests.findByConversationIdAndStatus(conversation.id(), "PENDING").isEmpty()) {
            throw conflict("GUIDE_CONVERSATION_BUSY", "本对话仍在处理上一条消息，请等待结果或切换对话");
        }
    }

    private GuideConversation owned(Long owner, String id) {
        return conversations.ownedForUpdate(id, owner).orElseThrow(() ->
                new DomainException(HttpStatus.NOT_FOUND, "GUIDE_CONVERSATION_NOT_FOUND", "对话不存在"));
    }

    private GuideTurnRequest request(GuideConversation conversation, String requestId) {
        return requests.findByConversationIdAndRequestId(conversation.id(), requestId).orElseThrow(() ->
                new DomainException(HttpStatus.NOT_FOUND, "GUIDE_REQUEST_NOT_FOUND", "请求不存在"));
    }

    private DomainException conflict(String code, String message) {
        return new DomainException(HttpStatus.CONFLICT, code, message);
    }

    private Instant now() { return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); }

    private GuideConversationViews.Summary summary(GuideConversation conversation) {
        return new GuideConversationViews.Summary(conversation.publicId(), conversation.title(), conversation.createdAt(), conversation.updatedAt());
    }

    private GuideConversationViews.Detail detail(GuideConversation conversation) {
        return new GuideConversationViews.Detail(conversation.publicId(), conversation.title(), conversation.createdAt(), conversation.updatedAt(),
                requests.findByConversationIdOrderByTurnSequenceAsc(conversation.id()).stream().map(r -> view(conversation, r)).toList());
    }

    private GuideConversationViews.Request view(GuideConversation conversation, GuideTurnRequest request) {
        return new GuideConversationViews.Request(request.requestId(), conversation.publicId(), request.sequence(), request.content(),
                request.status(), request.attempt(), result(request), request.errorCode() == null ? null :
                new GuideConversationViews.Error(request.errorCode(), request.errorMessage()), request.createdAt(), request.updatedAt());
    }

    private GuideChatResult result(GuideTurnRequest request) {
        if (request.resultJson() == null) return null;
        try {
            return mapper.readValue(request.resultJson(), GuideChatResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read saved guide result", exception);
        }
    }

    private void event(GuideConversation conversation, GuideTurnRequest request, String type) {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("conversationId", conversation.publicId());
        if (request != null) {
            payload.put("requestId", request.requestId());
            payload.put("attempt", request.attempt());
            payload.put("status", request.status());
        }
        outbox.save("GUIDE_CONVERSATION", conversation.id(), conversation.nextEventVersion(), type, payload);
    }
}
