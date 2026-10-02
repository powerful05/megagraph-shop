package com.megagraph.shop.delivery;

import com.megagraph.shop.api.DeliveryOfferResponse;
import com.megagraph.shop.api.DigitalDeliveryResponse;
import com.megagraph.shop.commerce.*;
import com.megagraph.shop.payment.CommerceOutboxWriter;
import com.megagraph.shop.shared.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Uses the same DataSource participating in JpaTransactionManager's transaction. No private asset
 * route.
 */
@Component
public class DigitalDeliveryStore {
  public static final String TERMS = "自建测试文件；支付成功后可领取，领取和下载均在支付后24小时内有效，重复领取不延长。";
  public static final String REFUND = "Demo规则：未领取可申请整单退款；领取后不支持退款，退款处理中禁止领取。";
  private final JdbcTemplate db;
  private final CommerceOutboxWriter outbox;

  public DigitalDeliveryStore(JdbcTemplate db, CommerceOutboxWriter outbox) {
    this.db = db;
    this.outbox = outbox;
  }

  public List<DeliveryOfferResponse> offers(Long merchant) {
    return db.query(
        "select o.*,m.name merchant_name,a.asset_version,a.content_hash from delivery_offer o join"
            + " merchant m on m.id=o.merchant_id join digital_asset a on a.id=o.asset_id where"
            + " o.merchant_id=? order by o.id desc",
        this::offer,
        merchant);
  }

  public List<DeliveryOfferResponse> publicOffers() {
    return db.query(publicSql() + " order by o.id desc", this::offer);
  }

  public DeliveryOfferResponse publicOffer(Long id) {
    return db.query(publicSql() + " and o.id=?", this::offer, id).stream()
        .findFirst()
        .orElseThrow(() -> new NotFoundException("DELIVERY_OFFER_NOT_FOUND", "数字文件当前不可购买"));
  }

  public DeliveryOfferResponse currentPublicOffer(Long id) {
    return db.query(publicSql() + " and o.id=? for update", this::offer, id).stream()
        .findFirst()
        .orElseThrow(() -> new NotFoundException("DELIVERY_OFFER_NOT_FOUND", "数字文件当前不可购买"));
  }

  public DeliveryOfferResponse currentMerchantOffer(Long merchant, Long id) {
    return db
        .query(
            "select o.*,m.name merchant_name,a.asset_version,a.content_hash from delivery_offer o"
                + " join merchant m on m.id=o.merchant_id join digital_asset a on a.id=o.asset_id"
                + " and a.merchant_id=o.merchant_id where o.merchant_id=? and o.id=? for update",
            this::offer,
            merchant,
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new NotFoundException("DELIVERY_OFFER_NOT_FOUND", "数字文件不存在"));
  }

  private String publicSql() {
    return "select o.*,m.name merchant_name,a.asset_version,a.content_hash from delivery_offer o"
        + " join merchant m on m.id=o.merchant_id join digital_asset a on a.id=o.asset_id"
        + " and a.merchant_id=o.merchant_id where o.status='PUBLISHED' and"
        + " o.confirmed_version=o.version and m.status='ACTIVE' and o.price>0 and"
        + " o.currency='CNY'";
  }

  public DeliveryOfferResponse merchantOffer(Long merchant, Long id) {
    return offers(merchant).stream()
        .filter(o -> o.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("DELIVERY_OFFER_NOT_FOUND", "数字文件不存在"));
  }

  private DeliveryOfferResponse offer(ResultSet r, int n) throws SQLException {
    return new DeliveryOfferResponse(
        r.getLong("id"),
        r.getLong("version"),
        r.getLong("merchant_id"),
        r.getString("merchant_name"),
        r.getString("delivery_type"),
        r.getString("title"),
        r.getString("description"),
        r.getBigDecimal("price"),
        r.getString("currency"),
        r.getString("status"),
        (Long) r.getObject("confirmed_version"),
        true,
        r.getLong("asset_version"),
        r.getString("content_hash"),
        TERMS,
        REFUND,
        24);
  }

  public DigitalDeliveryResponse delivery(Long order) {
    return db
        .query(
            "select * from delivery_purchase where order_id=?",
            (r, n) ->
                new DigitalDeliveryResponse(
                    r.getLong("offer_id"),
                    r.getLong("offer_version"),
                    r.getString("title"),
                    r.getString("description"),
                    r.getBigDecimal("unit_price"),
                    r.getString("currency"),
                    r.getLong("asset_version"),
                    r.getString("asset_hash"),
                    r.getString("delivery_terms"),
                    r.getString("refund_policy"),
                    r.getInt("availability_hours"),
                    entitlement(order),
                    hintForOrder(order, false),
                    hintForOrder(order, true)),
            order)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public DigitalDeliveryResponse.Entitlement entitlement(Long order) {
    return entitlement(order, false);
  }

  public DigitalDeliveryResponse.Entitlement currentEntitlement(Long order) {
    return entitlement(order, true);
  }

  private DigitalDeliveryResponse.Entitlement entitlement(Long order, boolean lock) {
    return db
        .query(
            "select * from digital_entitlement where order_id=?" + (lock ? " for update" : ""),
            (r, n) ->
                new DigitalDeliveryResponse.Entitlement(
                    r.getLong("id"),
                    r.getString("status"),
                    instant(r, "available_at"),
                    instant(r, "expires_at"),
                    instant(r, "claimed_at"),
                    instant(r, "revoked_at")),
            order)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private boolean hintForOrder(Long order, boolean download) {
    var e = entitlement(order);
    return e != null && hint(order, e.status(), e.expiresAt(), download);
  }

  private boolean hint(Long order, String status, Instant expires, boolean download) {
    if ("REVOKED".equals(status)
        || !Instant.now().isBefore(expires)
        || download && !"CLAIMED".equals(status)) return false;
    return db.queryForObject(
            "select count(*) from sales_order o join payment_order p on p.order_id=o.id where"
                + " o.id=? and o.status='PAID' and p.status='SUCCEEDED' and not exists(select 1"
                + " from after_sale_request a where a.order_id=o.id and a.status in"
                + " ('REQUESTED','REFUND_PROCESSING','REFUND_FAILED','REFUNDED'))",
            Long.class,
            order)
        == 1;
  }

  private Instant instant(ResultSet r, String key) throws SQLException {
    return r.getTimestamp(key) == null ? null : r.getTimestamp(key).toInstant();
  }

  /** Caller holds the sales_order lock; hooks execute in caller's payment/refund transaction. */
  public void paid(SalesOrder order) {
    if (!"DIGITAL_FILE".equals(order.getDeliveryType())) return;
    if (currentEntitlement(order.getId()) != null) return;
    Instant paid = order.getPaidAt();
    db.update(
        "insert into digital_entitlement(order_id,status,available_at,expires_at,version)"
            + " values(?,'AVAILABLE',?,?,0)",
        order.getId(),
        java.sql.Timestamp.from(paid),
        java.sql.Timestamp.from(paid.plus(Duration.ofHours(24))));
    var e = currentEntitlement(order.getId());
    outbox.save(
        "DIGITAL_ENTITLEMENT",
        e.id(),
        0L,
        "DIGITAL_ENTITLEMENT_AVAILABLE",
        Map.of("orderId", order.getId(), "expiresAt", e.expiresAt()));
  }

  public void refunded(SalesOrder order) {
    if (!"DIGITAL_FILE".equals(order.getDeliveryType())) return;
    var e = currentEntitlement(order.getId());
    if (e == null) throw new IllegalStateException("Paid digital order missing entitlement");
    if ("CLAIMED".equals(e.status()))
      throw new ConflictException("DIGITAL_ALREADY_CLAIMED", "测试数字文件已领取，不能退款");
    if ("REVOKED".equals(e.status())) return;
    db.update(
        "update digital_entitlement set status='REVOKED',revoked_at=?,version=version+1 where"
            + " order_id=?",
        java.sql.Timestamp.from(Instant.now()),
        order.getId());
    outbox.save(
        "DIGITAL_ENTITLEMENT",
        e.id(),
        1L,
        "DIGITAL_ENTITLEMENT_REVOKED",
        Map.of("orderId", order.getId()));
  }

  public void requireRefundable(SalesOrder order) {
    if (!"DIGITAL_FILE".equals(order.getDeliveryType())) return;
    var e = currentEntitlement(order.getId());
    if (e != null && "CLAIMED".equals(e.status()))
      throw new ConflictException("DIGITAL_ALREADY_CLAIMED", "测试数字文件已领取，不能申请退款");
  }
}
