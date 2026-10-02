package com.megagraph.shop.delivery;

import com.megagraph.shop.api.*;
import com.megagraph.shop.commerce.*;
import com.megagraph.shop.payment.CommerceOutboxWriter;
import com.megagraph.shop.shared.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AppointmentStore {
  public static final String TERMS = "固定时段测试预约；付款前预占最长15分钟且不超过开始时刻；支付后确认名额，须商家显式模拟测试完成，不提供真实服务。";
  public static final String REFUND = "Demo规则：尚未模拟测试完成可申请整单退款；已模拟完成不退款，申请或处理退款期间禁止完成。";
  final JdbcTemplate db;
  final CommerceOutboxWriter outbox;

  public AppointmentStore(JdbcTemplate db, CommerceOutboxWriter outbox) {
    this.db = db;
    this.outbox = outbox;
  }

  String offerSql() {
    return "select o.*,m.name merchant_name from appointment_offer o join merchant m on"
        + " m.id=o.merchant_id";
  }

  String publicCondition() {
    return " o.status='PUBLISHED' and o.confirmed_version=o.version and o.price>0 and"
        + " o.currency='CNY' and m.status='ACTIVE'";
  }

  public List<AppointmentOfferResponse> offers(Long merchant) {
    return db.query(
        offerSql() + " where o.merchant_id=? order by o.id desc",
        (r, n) -> offer(r, false),
        merchant);
  }

  public List<AppointmentOfferResponse> publicOffers() {
    return db
        .query(
            offerSql() + " where " + publicCondition() + " order by o.id desc",
            (r, n) -> offer(r, true))
        .stream()
        .filter(o -> !o.slots().isEmpty())
        .toList();
  }

  public AppointmentOfferResponse publicOffer(Long id) {
    return db
        .query(
            offerSql() + " where " + publicCondition() + " and o.id=?",
            (r, n) -> offer(r, true),
            id)
        .stream()
        .filter(o -> !o.slots().isEmpty())
        .findFirst()
        .orElseThrow(() -> missing());
  }

  public void currentAccountShared(Long id) {
    boolean mysql =
        db.execute(
            (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                c -> c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL"));
    db.queryForObject(
        "select id from app_user where id=?" + (mysql ? " lock in share mode" : " for update"),
        Long.class,
        id);
  }

  public Map<String, Object> currentMerchant(Long id) {
    boolean mysql =
        db.execute(
            (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                c -> c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL"));
    return db.queryForMap(
        "select status,name from merchant where id=?"
            + (mysql ? " lock in share mode" : " for update"),
        id);
  }

  public AppointmentOfferResponse currentOffer(Long id, Long merchant, boolean publicOnly) {
    String condition = publicOnly ? "id=?" : "merchant_id=? and id=?";
    Object[] args = publicOnly ? new Object[] {id} : new Object[] {merchant, id};
    var o =
        db
            .query(
                "select o.*,'' merchant_name from appointment_offer o where "
                    + condition
                    + " for update",
                (r, n) -> offer(r, false),
                args)
            .stream()
            .findFirst()
            .orElseThrow(() -> missing());
    var m = currentMerchant(o.merchantId());
    if (publicOnly
        && (!"ACTIVE".equals(m.get("status"))
            || !"PUBLISHED".equals(o.status())
            || !Objects.equals(o.confirmedVersion(), o.version())
            || o.price().signum() <= 0
            || !"CNY".equals(o.currency()))) throw missing();
    return new AppointmentOfferResponse(
        o.id(),
        o.version(),
        o.merchantId(),
        (String) m.get("name"),
        o.deliveryType(),
        o.title(),
        o.description(),
        o.price(),
        o.currency(),
        o.status(),
        o.confirmedVersion(),
        o.testOnly(),
        o.deliveryTerms(),
        o.refundPolicy(),
        o.slots());
  }

  public AppointmentOfferResponse merchantOffer(Long merchant, Long id) {
    return offers(merchant).stream()
        .filter(o -> id.equals(o.id()))
        .findFirst()
        .orElseThrow(() -> missing());
  }

  NotFoundException missing() {
    return new NotFoundException("APPOINTMENT_OFFER_NOT_FOUND", "当前测试预约不存在或不可购买");
  }

  AppointmentOfferResponse offer(ResultSet r, boolean publicOnly) throws SQLException {
    return new AppointmentOfferResponse(
        r.getLong("id"),
        r.getLong("version"),
        r.getLong("merchant_id"),
        r.getString("merchant_name"),
        "APPOINTMENT",
        r.getString("title"),
        r.getString("description"),
        r.getBigDecimal("price"),
        r.getString("currency"),
        r.getString("status"),
        (Long) r.getObject("confirmed_version"),
        true,
        TERMS,
        REFUND,
        slots(r.getLong("id"), publicOnly));
  }

  public List<AppointmentOfferResponse.Slot> slots(Long offer, boolean publicOnly) {
    var values =
        db.query(
            "select * from appointment_slot where offer_id=? order by start_at,id",
            (r, n) -> slot(r, false),
            offer);
    return publicOnly
        ? values.stream()
            .filter(
                s ->
                    "OPEN".equals(s.status())
                        && s.startAt().isAfter(Instant.now())
                        && s.remaining() > 0)
            .limit(10)
            .toList()
        : values;
  }

  public AppointmentOfferResponse.Slot currentSlot(Long id, Long offer) {
    return db
        .query(
            "select * from appointment_slot where id=? and offer_id=? for update",
            (r, n) -> slot(r, true),
            id,
            offer)
        .stream()
        .findFirst()
        .orElseThrow(() -> new NotFoundException("APPOINTMENT_SLOT_NOT_FOUND", "时段不存在"));
  }

  AppointmentOfferResponse.Slot slot(ResultSet r, boolean lock) throws SQLException {
    Long id = r.getLong("id");
    int used = used(id, lock, Instant.now());
    return new AppointmentOfferResponse.Slot(
        id,
        r.getLong("offer_id"),
        r.getLong("version"),
        instant(r, "start_at"),
        instant(r, "end_at"),
        r.getInt("capacity"),
        Math.max(0, r.getInt("capacity") - used),
        r.getString("status"));
  }

  int used(Long slot, boolean lock, Instant now) {
    return db
        .query(
            "select status,expires_at from appointment_reservation where slot_id=?"
                + (lock ? " for update" : ""),
            (r, n) ->
                ("CONFIRMED".equals(r.getString(1))
                        || "FULFILLED".equals(r.getString(1))
                        || "HELD".equals(r.getString(1))
                            && r.getTimestamp(2).toInstant().isAfter(now))
                    ? 1
                    : 0,
            slot)
        .stream()
        .mapToInt(Integer::intValue)
        .sum();
  }

  static Instant instant(ResultSet r, String k) throws SQLException {
    return r.getTimestamp(k) == null ? null : r.getTimestamp(k).toInstant();
  }

  public AppointmentResponse.Reservation reservation(Long order, boolean lock) {
    return db
        .query(
            "select * from appointment_reservation where order_id=?" + (lock ? " for update" : ""),
            (r, n) ->
                new AppointmentResponse.Reservation(
                    r.getLong("id"),
                    r.getLong("version"),
                    r.getString("status"),
                    instant(r, "held_at"),
                    instant(r, "expires_at"),
                    instant(r, "confirmed_at"),
                    instant(r, "released_at"),
                    r.getString("release_reason"),
                    instant(r, "fulfilled_at")),
            order)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public AppointmentResponse appointment(Long order) {
    return db
        .query(
            "select * from appointment_purchase where order_id=?",
            (r, n) ->
                new AppointmentResponse(
                    r.getLong("offer_id"),
                    r.getLong("offer_version"),
                    r.getString("title"),
                    r.getString("description"),
                    r.getBigDecimal("unit_price"),
                    r.getString("currency"),
                    r.getLong("slot_id"),
                    r.getLong("slot_version"),
                    instant(r, "start_at"),
                    instant(r, "end_at"),
                    r.getInt("capacity"),
                    r.getString("delivery_terms"),
                    r.getString("refund_policy"),
                    true,
                    reservation(order, false),
                    completeHint(order)),
            order)
        .stream()
        .findFirst()
        .orElse(null);
  }

  boolean completeHint(Long order) {
    var r = reservation(order, false);
    return r != null
        && "CONFIRMED".equals(r.status())
        && db.queryForObject(
                "select count(*) from sales_order o join payment_order p on p.order_id=o.id where"
                    + " o.id=? and o.status='PAID' and p.status='SUCCEEDED' and not exists(select 1"
                    + " from after_sale_request a where a.order_id=o.id and a.status in"
                    + " ('REQUESTED','REFUND_PROCESSING','REFUND_FAILED','REFUNDED')) and not"
                    + " exists(select 1 from payment_channel_event e where"
                    + " e.matched_payment_order_id=p.id and e.processing_status='REVIEW_REQUIRED')",
                Long.class,
                order)
            == 1;
  }

  /** Caller always holds order, then payment (when applicable). No inverse order acquisition. */
  public AppointmentResponse.Reservation lockReservation(SalesOrder order) {
    var ids =
        db.queryForList(
            "select slot_id from appointment_purchase where order_id=?", Long.class, order.getId());
    if (ids.size() != 1) throw new IllegalStateException("Appointment purchase missing");
    db.queryForObject(
        "select id from appointment_slot where id=? for update", Long.class, ids.getFirst());
    var r = reservation(order.getId(), true);
    if (r == null) throw new IllegalStateException("Appointment reservation missing");
    return r;
  }

  public void event(AppointmentResponse.Reservation r, Long order, long next, String type) {
    outbox.save(
        "APPOINTMENT_RESERVATION", r.id(), next, type, Map.of("orderId", order, "testOnly", true));
  }

  public boolean canConfirm(SalesOrder order) {
    if (!"APPOINTMENT".equals(order.getDeliveryType())) return true;
    var r = lockReservation(order);
    Instant now = Instant.now();
    Instant start =
        db.queryForObject(
                "select start_at from appointment_purchase where order_id=?",
                Timestamp.class,
                order.getId())
            .toInstant();
    return "HELD".equals(r.status())
        && now.isBefore(r.expiresAt())
        && now.isBefore(order.getExpiresAt())
        && now.isBefore(start);
  }

  public void paid(SalesOrder order) {
    if (!"APPOINTMENT".equals(order.getDeliveryType())) return;
    var r = lockReservation(order);
    if (!"HELD".equals(r.status()))
      throw new ConflictException("APPOINTMENT_NOT_HELD", "预约不再处于预占状态");
    db.update(
        "update appointment_reservation set status='CONFIRMED',confirmed_at=?,version=version+1"
            + " where order_id=?",
        Timestamp.from(Instant.now()),
        order.getId());
    event(r, order.getId(), r.version() + 1, "APPOINTMENT_CONFIRMED");
  }

  public void release(SalesOrder order, String reason) {
    if (!"APPOINTMENT".equals(order.getDeliveryType())) return;
    var r = lockReservation(order);
    if ("RELEASED".equals(r.status())) return;
    if ("FULFILLED".equals(r.status()))
      throw new ConflictException("APPOINTMENT_ALREADY_FULFILLED", "测试预约已经模拟完成");
    if (!"REFUNDED".equals(reason) && !"HELD".equals(r.status()))
      throw new ConflictException("APPOINTMENT_RELEASE_INVALID", "只能取消尚未支付的预占");
    db.update(
        "update appointment_reservation set"
            + " status='RELEASED',released_at=?,release_reason=?,version=version+1 where"
            + " order_id=?",
        Timestamp.from(Instant.now()),
        reason,
        order.getId());
    event(r, order.getId(), r.version() + 1, "APPOINTMENT_RELEASED");
  }

  public void requireRefundable(SalesOrder order) {
    if (!"APPOINTMENT".equals(order.getDeliveryType())) return;
    var r = lockReservation(order);
    if (!"CONFIRMED".equals(r.status()))
      throw new ConflictException("APPOINTMENT_NOT_REFUNDABLE", "仅尚未模拟完成的已确认测试预约可退款");
  }
}
