package com.megagraph.shop.guide;

import com.megagraph.shop.shared.DomainException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("guide")
public class GuideConversationService {
    private final GuideConversationStore store;
    private final GuideRequestExecutor executor;

    public GuideConversationService(GuideConversationStore store, GuideRequestExecutor executor) {
        this.store = store;
        this.executor = executor;
    }

    public GuideConversationViews.Request send(Long owner, String id, String requestId, String content) {
        return execute(owner, id, store.begin(owner, id, requestId, content));
    }

    public GuideConversationViews.Request retry(Long owner, String id, String requestId) {
        return execute(owner, id, store.retry(owner, id, requestId));
    }

    private GuideConversationViews.Request execute(Long owner, String id, GuideConversationViews.Work work) {
        if (!work.execute()) return work.request();
        GuideChatResult result;
        try {
            result = executor.execute(work.request().requestId(), work.turns());
        } catch (DomainException failure) {
            return store.finish(owner, id, work.request().requestId(), work.request().attempt(), null, failure);
        }
        return store.finish(owner, id, work.request().requestId(), work.request().attempt(), result, null);
    }
}
