package com.megagraph.shop.guide;

import com.megagraph.shop.shared.DomainException;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile("guide")
public class GuideRequestExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(GuideRequestExecutor.class);
    private final GuideAgent agent;
    private final boolean demoCatalogAvailable;
    private final Semaphore concurrentRequests = new Semaphore(2);

    public GuideRequestExecutor(GuideAgent agent, Environment environment) {
        this.agent = agent;
        this.demoCatalogAvailable = environment.acceptsProfiles(Profiles.of("local"));
    }

    public GuideChatResult execute(String requestId, List<GuideModel.Turn> turns) {
        if (!concurrentRequests.tryAcquire()) {
            throw new DomainException(HttpStatus.TOO_MANY_REQUESTS, "GUIDE_BUSY", "导购正在处理其他请求，请稍后重试");
        }
        long started = System.nanoTime();
        org.slf4j.MDC.put("guideRequestId", requestId);
        try {
            return agent.chat(turns, demoCatalogAvailable);
        } catch (DomainException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOG.warn("Guide request failed: {}", exception.getClass().getSimpleName());
            throw new DomainException(HttpStatus.SERVICE_UNAVAILABLE, "GUIDE_MODEL_FAILED", "模型暂时无法回复，请稍后重试");
        } finally {
            LOG.info("Guide request requestId={}, turns={}, milliseconds={}", requestId, turns.size(),
                    (System.nanoTime() - started) / 1_000_000);
            org.slf4j.MDC.remove("guideRequestId");
            concurrentRequests.release();
        }
    }
}
