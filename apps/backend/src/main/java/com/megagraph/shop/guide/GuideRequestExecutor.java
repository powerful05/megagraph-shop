package com.megagraph.shop.guide;

import com.megagraph.shop.shared.DomainException;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class GuideRequestExecutor {
  private static final Logger LOG = LoggerFactory.getLogger(GuideRequestExecutor.class);
  private final GuideAgent agent;
  private final com.megagraph.shop.delivery.DigitalDeliveryService digital;
  private final boolean modelAvailable;
  private final boolean demoCatalogAvailable;
  private final Semaphore concurrentRequests = new Semaphore(2);

  public GuideRequestExecutor(
      GuideAgent agent,
      Environment environment,
      com.megagraph.shop.delivery.DigitalDeliveryService digital) {
    this.agent = agent;
    this.digital = digital;
    this.modelAvailable = environment.acceptsProfiles(Profiles.of("guide"));
    this.demoCatalogAvailable = environment.acceptsProfiles(Profiles.of("local"));
  }

  public GuideChatResult execute(String requestId, List<GuideModel.Turn> turns) {
    GuideAgent.validateHistory(turns);
    if (!concurrentRequests.tryAcquire()) {
      throw new DomainException(HttpStatus.TOO_MANY_REQUESTS, "GUIDE_BUSY", "导购正在处理其他请求，请稍后重试");
    }
    long started = System.nanoTime();
    org.slf4j.MDC.put("guideRequestId", requestId);
    try {
      String latest = turns.getLast().content();
      if (latest.matches("(?is).*(数字文件|测试文件|电子文件|digital file|下载文件|领取文件).*")) {
        var offers = digital.publicOffers().stream().limit(3).toList();
        return new GuideChatResult(
            offers.isEmpty()
                ? "目前没有商家已确认并公开的数字测试文件。"
                : "以下是商家已确认的自建数字测试文件。价格和交付约定来自当前资料；请选择文件，确认后再购买。",
            List.of(),
            List.of(),
            "按商家确认资料检索，购买前请核对价格与交付期限。",
            List.of(),
            offers,
            "JAVA_DIGITAL");
      }
      if (!modelAvailable)
        return new GuideChatResult(
            "当前未启用真实模型导购。可查询数字测试文件，或查看本地Demo商品。",
            List.of(),
            List.of(),
            "",
            List.of(),
            List.of(),
            "NO_MODEL");
      return agent.chat(turns, demoCatalogAvailable);
    } catch (DomainException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      LOG.warn("Guide request failed: {}", exception.getClass().getSimpleName());
      throw new DomainException(
          HttpStatus.SERVICE_UNAVAILABLE, "GUIDE_MODEL_FAILED", "模型暂时无法回复，请稍后重试");
    } finally {
      LOG.info(
          "Guide request requestId={}, turns={}, milliseconds={}",
          requestId,
          turns.size(),
          (System.nanoTime() - started) / 1_000_000);
      org.slf4j.MDC.remove("guideRequestId");
      concurrentRequests.release();
    }
  }
}
