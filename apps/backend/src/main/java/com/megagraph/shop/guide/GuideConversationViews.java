package com.megagraph.shop.guide;

import java.time.Instant;
import java.util.List;

public final class GuideConversationViews {
    private GuideConversationViews() {}
    public record Summary(String id, String title, Instant createdAt, Instant updatedAt) {}
    public record Error(String code, String message) {}
    public record Request(String requestId, String conversationId, long sequence, String content,
                          String status, int attempt, GuideChatResult result, Error error,
                          Instant createdAt, Instant updatedAt) {}
    public record Detail(String id, String title, Instant createdAt, Instant updatedAt, List<Request> requests) {}
    public record Work(Request request, boolean execute, List<GuideModel.Turn> turns) {}
}
