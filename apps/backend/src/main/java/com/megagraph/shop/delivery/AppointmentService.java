package com.megagraph.shop.delivery;

import com.megagraph.shop.api.*;
import com.megagraph.shop.commerce.*;
import com.megagraph.shop.payment.CommerceOutboxWriter;
import com.megagraph.shop.shared.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.core.env.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentService {
  final JdbcTemplate db;
  final AppointmentStore store;
  final SalesOrderRepository orders;
  final PaymentOrderRepository payments;
  final OrderMapper mapper;
  final PaymentGateway gateway;
  final CommerceOutboxWriter outbox;
  final Environment env;

  public AppointmentService(
      JdbcTemplate db,
      AppointmentStore store,
      SalesOrderRepository orders,
      PaymentOrderRepository payments,
      OrderMapper mapper,
      PaymentGateway gateway,
      CommerceOutboxWriter outbox,
      Environment env) {
    this.db = db;
    this.store = store;
    this.orders = orders;
    this.payments = payments;
    this.mapper = mapper;
    this.gateway = gateway;
    this.outbox = outbox;
    this.env = env;
  }

  public boolean available() {
    return env.acceptsProfiles(Profiles.of("test"))
        || env.acceptsProfiles(Profiles.of("local"))
            && env.acceptsProfiles(Profiles.of("supply-demo"));
  }

  void demo() {
    if (!available())
      throw new DomainException(
          HttpStatus.SERVICE_UNAVAILABLE, "APPOINTMENT_DEMO_UNAVAILABLE", "当前环境未开放测试预约");
  }

  String key(String k) {
    if (k == null || k.isBlank() || k.strip().length() > 128)
      throw bad("IDEMPOTENCY_KEY_INVALID", "幂等键不能为空且不超过128字符");
    return k.strip();
  }

  DomainException bad(String code, String text) {
    return new DomainException(HttpStatus.BAD_REQUEST, code, text);
  }

  String hash(Object... fields) {
    try {
      StringBuilder b = new StringBuilder();
      for (Object f : fields) {
        String s = String.valueOf(f);
        b.append(s.length()).append(':').append(s);
      }
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(b.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  void valid(DeliveryOfferRequest r, boolean create) {
    if (create && !"APPOINTMENT".equals(r.deliveryType())
        || r.deliveryType() != null && !"APPOINTMENT".equals(r.deliveryType())
        || r.title() == null
        || !r.title().contains("测试")
        || r.description() == null
        || !r.description().contains("测试")
        || !r.description().contains("不实际")
        || !"CNY".equals(r.currency())
        || r.price() == null
        || r.price().signum() <= 0
        || r.price().scale() > 2
        || r.price().precision() - r.price().scale() > 10)
      throw bad("APPOINTMENT_OFFER_INVALID", "仅支持标题标注测试、描述明确不实际履约、正价CNY的测试预约");
  }

  long generated(String sql, Object... args) {
    var k = new org.springframework.jdbc.support.GeneratedKeyHolder();
    db.update(
        c -> {
          var ps = c.prepareStatement(sql, new String[] {"id"});
          for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
          return ps;
        },
        k);
    return k.getKey().longValue();
  }

  void merchantMutex(Long m) {
    db.update("insert ignore into appointment_merchant_lock(merchant_id) values(?)", m);
    if (db.queryForList(
                "select merchant_id from appointment_merchant_lock where merchant_id=? for update",
                Long.class,
                m)
            .size()
        != 1) throw new IllegalStateException("Merchant mutex missing");
  }

  void accountMutex(Long u) {
    db.update("insert ignore into appointment_account_lock(user_id) values(?)", u);
    if (db.queryForList(
                "select user_id from appointment_account_lock where user_id=? for update",
                Long.class,
                u)
            .size()
        != 1) throw new IllegalStateException("Account mutex missing");
  }

  public List<AppointmentOfferResponse> offers(Long m) {
    demo();
    return store.offers(m);
  }

  public List<AppointmentOfferResponse> publicOffers() {
    return available() ? store.publicOffers() : List.of();
  }

  public AppointmentOfferResponse publicOffer(Long id) {
    demo();
    return store.publicOffer(id);
  }

  void offerEvent(Long id, long v, String type) {
    outbox.save("APPOINTMENT_OFFER", id, v, type, Map.of("offerId", id, "testOnly", true));
  }

  @Transactional
  public AppointmentOfferResponse create(Long m, String k, DeliveryOfferRequest r) {
    demo();
    valid(r, true);
    k = key(k);
    String h =
        hash(
            r.title().strip(),
            r.description().strip(),
            r.price().stripTrailingZeros().toPlainString(),
            "APPOINTMENT",
            "CNY");
    merchantMutex(m);
    var existing =
        db.queryForList(
            "select id,payload_hash from appointment_offer where merchant_id=? and"
                + " idempotency_key=? for update",
            m,
            k);
    if (!existing.isEmpty()) {
      if (!h.equals(existing.getFirst().get("payload_hash")))
        throw new ConflictException("APPOINTMENT_IDEMPOTENCY_CONFLICT", "同键资料不同");
      return store.merchantOffer(m, ((Number) existing.getFirst().get("id")).longValue());
    }
    long id =
        generated(
            "insert into"
                + " appointment_offer(merchant_id,title,description,price,currency,status,version,idempotency_key,payload_hash)"
                + " values(?,?,?,?,'CNY','DRAFT',0,?,?)",
            m,
            r.title().strip(),
            r.description().strip(),
            r.price(),
            k,
            h);
    offerEvent(id, 0, "APPOINTMENT_OFFER_CREATED");
    return store.merchantOffer(m, id);
  }

  AppointmentOfferResponse current(Long m, Long id, Long v) {
    var o = store.currentOffer(id, m, false);
    if (!Objects.equals(v, o.version()))
      throw new ConflictException("APPOINTMENT_VERSION_CONFLICT", "预约资料已更新，请刷新");
    return o;
  }

  @Transactional
  public AppointmentOfferResponse edit(Long m, Long id, DeliveryOfferRequest r) {
    demo();
    valid(r, false);
    merchantMutex(m);
    var o = current(m, id, r.version());
    db.update(
        "update appointment_offer set"
            + " title=?,description=?,price=?,version=version+1,status='DRAFT',confirmed_version=null"
            + " where id=?",
        r.title().strip(),
        r.description().strip(),
        r.price(),
        id);
    offerEvent(id, o.version() + 1, "APPOINTMENT_OFFER_EDITED");
    return store.merchantOffer(m, id);
  }

  @Transactional
  public AppointmentOfferResponse transition(Long m, Long id, Long v, String action) {
    demo();
    merchantMutex(m);
    var o = current(m, id, v);
    long n = o.version() + 1;
    switch (action) {
      case "confirm" -> {
        if (o.slots().stream()
            .noneMatch(s -> "OPEN".equals(s.status()) && s.startAt().isAfter(Instant.now())))
          throw new ConflictException("APPOINTMENT_SLOT_REQUIRED", "需至少一个未来开放测试时段");
        db.update(
            "update appointment_offer set version=?,confirmed_version=? where id=?", n, n, id);
      }
      case "publish" -> {
        if (!Objects.equals(o.confirmedVersion(), o.version()))
          throw new ConflictException("APPOINTMENT_CONFIRMATION_REQUIRED", "先确认当前版本");
        if (!"ACTIVE".equals(store.currentMerchant(m).get("status")))
          throw new ConflictException("MERCHANT_INACTIVE", "商家不可发布");
        db.update(
            "update appointment_offer set version=?,confirmed_version=?,status='PUBLISHED' where"
                + " id=?",
            n,
            n,
            id);
      }
      case "off-shelf" ->
          db.update(
              "update appointment_offer set version=?,confirmed_version=null,status='OFF_SHELF'"
                  + " where id=?",
              n,
              id);
      default -> throw new IllegalArgumentException();
    }
    offerEvent(id, n, "APPOINTMENT_OFFER_" + action.toUpperCase(Locale.ROOT).replace('-', '_'));
    return store.merchantOffer(m, id);
  }

  @Transactional
  public AppointmentOfferResponse addSlot(Long m, Long id, String idem, AppointmentSlotRequest r) {
    demo();
    String k = key(idem), h = hash(id, r.version(), r.startAt(), r.endAt(), r.capacity());
    merchantMutex(m);
    var exist =
        db.queryForList(
            "select offer_id,payload_hash from appointment_slot where merchant_id=? and"
                + " idempotency_key=? for update",
            m,
            k);
    if (!exist.isEmpty()) {
      if (!h.equals(exist.getFirst().get("payload_hash"))
          || !id.equals(((Number) exist.getFirst().get("offer_id")).longValue()))
        throw new ConflictException("APPOINTMENT_SLOT_IDEMPOTENCY_CONFLICT", "时段键已用于其他资料");
      return store.merchantOffer(m, id);
    }
    var o = current(m, id, r.version());
    Instant now = Instant.now();
    if (r.startAt() == null
        || r.endAt() == null
        || r.capacity() == null
        || r.capacity() < 1
        || r.capacity() > 50
        || !r.startAt().isAfter(now)
        || !r.endAt().isAfter(r.startAt())
        || Duration.between(r.startAt(), r.endAt()).compareTo(Duration.ofHours(4)) > 0
        || r.startAt().isAfter(now.plus(Duration.ofDays(30))))
      throw bad("APPOINTMENT_SLOT_INVALID", "时段需为未来30天内带时区时间、时长不超过4小时、容量1至50");
    long s =
        generated(
            "insert into"
                + " appointment_slot(offer_id,merchant_id,start_at,end_at,capacity,status,version,idempotency_key,payload_hash)"
                + " values(?,?,?,?,?,'OPEN',0,?,?)",
            id,
            m,
            Timestamp.from(r.startAt()),
            Timestamp.from(r.endAt()),
            r.capacity(),
            k,
            h);
    db.update(
        "update appointment_offer set version=version+1,confirmed_version=null,status='DRAFT' where"
            + " id=?",
        id);
    offerEvent(id, o.version() + 1, "APPOINTMENT_SLOT_ADDED");
    outbox.save(
        "APPOINTMENT_SLOT",
        s,
        0L,
        "APPOINTMENT_SLOT_CREATED",
        Map.of("offerId", id, "testOnly", true));
    return store.merchantOffer(m, id);
  }

  @Transactional
  public AppointmentOfferResponse closeSlot(Long m, Long id, Long slot, Long v) {
    demo();
    merchantMutex(m);
    store.currentOffer(id, m, false);
    var s = store.currentSlot(slot, id);
    if (!Objects.equals(s.version(), v))
      throw new ConflictException("APPOINTMENT_SLOT_VERSION_CONFLICT", "时段已更新");
    if (!"CLOSED".equals(s.status())) {
      db.update("update appointment_slot set status='CLOSED',version=version+1 where id=?", slot);
      outbox.save(
          "APPOINTMENT_SLOT",
          slot,
          s.version() + 1,
          "APPOINTMENT_SLOT_CLOSED",
          Map.of("offerId", id, "testOnly", true));
    }
    return store.merchantOffer(m, id);
  }

  long integer(Object v, String name) {
    if (!(v instanceof Number)) throw bad("APPOINTMENT_INPUT_INVALID", name + "必须为非负整数");
    try {
      long n = new BigDecimal(v.toString()).longValueExact();
      if (n < 0) throw new ArithmeticException();
      return n;
    } catch (ArithmeticException e) {
      throw bad("APPOINTMENT_INPUT_INVALID", name + "超出整数范围或为小数");
    }
  }

  long[] input(AppointmentOrderRequest r) {
    if (r == null
        || r.items() == null
        || r.items().size() != 1
        || r.items().getFirst() == null
        || !r.items()
            .getFirst()
            .keySet()
            .equals(
                Set.of(
                    "offerId",
                    "slotId",
                    "quantity",
                    "expectedOfferVersion",
                    "expectedSlotVersion")))
      throw bad("APPOINTMENT_SINGLE_ITEM_REQUIRED", "仅支持一项测试预约，不得混入实物、数字文件或客户端事实");
    var i = r.items().getFirst();
    if (integer(i.get("quantity"), "quantity") != 1)
      throw bad("APPOINTMENT_SINGLE_ITEM_REQUIRED", "预约数量必须为1");
    long[] v = {
      integer(i.get("offerId"), "offerId"),
      integer(i.get("slotId"), "slotId"),
      integer(i.get("expectedOfferVersion"), "expectedOfferVersion"),
      integer(i.get("expectedSlotVersion"), "expectedSlotVersion")
    };
    if (v[0] == 0 || v[1] == 0) throw bad("APPOINTMENT_INPUT_INVALID", "offerId和slotId必须为正整数");
    return v;
  }

  @Transactional
  public OrderResponse purchase(Long u, String idem, AppointmentOrderRequest request) {
    demo();
    long[] i = input(request);
    String k = key(idem), h = hash("APPOINTMENT", i[0], i[1], 1, i[2], i[3]);
    accountMutex(u);
    store.currentAccountShared(u);
    var old = orders.findByUserIdAndIdempotencyKey(u, k).orElse(null);
    if (old != null) {
      var hashes =
          db.queryForList(
              "select payload_hash from appointment_purchase where order_id=?",
              String.class,
              old.getId());
      if (!"APPOINTMENT".equals(old.getDeliveryType())
          || hashes.size() != 1
          || !h.equals(hashes.getFirst()))
        throw new ConflictException("APPOINTMENT_IDEMPOTENCY_CONFLICT", "购买键已用于其他订单或载荷");
      return mapper.toResponse(old);
    }
    var o = store.currentOffer(i[0], null, true);
    var s = store.currentSlot(i[1], i[0]);
    if (o.version() != i[2] || s.version() != i[3])
      throw new ConflictException("OFFER_CHANGED", "预约或时段已更新，请重新确认");
    Instant now = Instant.now();
    if (!"OPEN".equals(s.status()) || !s.startAt().isAfter(now) || s.remaining() < 1)
      throw new ConflictException("APPOINTMENT_SLOT_UNAVAILABLE", "当前时段无可用名额");
    Instant expires = now.plus(Duration.ofMinutes(15));
    if (s.startAt().isBefore(expires)) expires = s.startAt();
    SalesOrder order =
        new SalesOrder(
            "MGA" + UUID.randomUUID().toString().replace("-", "").substring(0, 23),
            u,
            o.merchantId(),
            k,
            "CNY",
            o.price(),
            "测试预约",
            "不适用",
            "不实际履约",
            expires);
    order.markAppointment();
    order = orders.saveAndFlush(order);
    db.update(
        "insert into"
            + " appointment_purchase(order_id,offer_id,offer_version,title,description,unit_price,currency,slot_id,slot_version,start_at,end_at,capacity,delivery_terms,refund_policy,payload_hash)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        order.getId(),
        o.id(),
        o.version(),
        o.title(),
        o.description(),
        o.price(),
        "CNY",
        s.id(),
        s.version(),
        Timestamp.from(s.startAt()),
        Timestamp.from(s.endAt()),
        s.capacity(),
        TERMS(),
        REFUND(),
        h);
    long r =
        generated(
            "insert into"
                + " appointment_reservation(order_id,slot_id,version,status,held_at,expires_at)"
                + " values(?,?,0,'HELD',?,?)",
            order.getId(),
            s.id(),
            Timestamp.from(now),
            Timestamp.from(expires));
    PaymentSession p = gateway.createPayment(order.getOrderNo(), o.price(), "CNY");
    payments.saveAndFlush(
        new PaymentOrder(order.getId(), p.provider(), p.providerPaymentId(), o.price(), "CNY"));
    outbox.save(
        "ORDER",
        order.getId(),
        order.getVersion(),
        "ORDER_CREATED",
        Map.of("orderId", order.getId(), "deliveryType", "APPOINTMENT", "testOrder", true));
    outbox.save(
        "APPOINTMENT_RESERVATION",
        r,
        0L,
        "APPOINTMENT_HELD",
        Map.of("orderId", order.getId(), "expiresAt", expires, "testOnly", true));
    return mapper.toResponse(order);
  }

  String TERMS() {
    return AppointmentStore.TERMS;
  }

  String REFUND() {
    return AppointmentStore.REFUND;
  }

  @Transactional
  public OrderResponse complete(Long m, Long id, String idem, Long version) {
    demo();
    String k = key(idem), h = hash(id, version);
    var order =
        orders
            .findByIdForUpdate(id)
            .filter(o -> m.equals(o.getMerchantId()) && "APPOINTMENT".equals(o.getDeliveryType()))
            .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "测试预约订单不存在"));
    var payment = payments.findByOrderIdForUpdate(id).orElseThrow();
    merchantMutex(m);
    var keys =
        db.queryForList(
            "select order_id,payload_hash from appointment_complete_key where merchant_id=? and"
                + " idempotency_key=? for update",
            m,
            k);
    if (!keys.isEmpty()) {
      if (!h.equals(keys.getFirst().get("payload_hash")))
        throw new ConflictException("APPOINTMENT_COMPLETE_IDEMPOTENCY_CONFLICT", "完成键已用于其他订单或版本");
      return mapper.toResponse(order);
    }
    var r = store.lockReservation(order);
    if (!Objects.equals(version, r.version()))
      throw new ConflictException("APPOINTMENT_RESERVATION_VERSION_CONFLICT", "预约状态已更新");
    boolean blocked =
        !db.queryForList(
                    "select id from after_sale_request where order_id=? and status in"
                        + " ('REQUESTED','REFUND_PROCESSING','REFUND_FAILED','REFUNDED') for"
                        + " update",
                    Long.class,
                    id)
                .isEmpty()
            || !db.queryForList(
                    "select id from payment_channel_event where matched_payment_order_id=? and"
                        + " processing_status='REVIEW_REQUIRED' for update",
                    Long.class,
                    payment.getId())
                .isEmpty();
    if (order.getStatus() != OrderStatus.PAID
        || payment.getStatus() != PaymentStatus.SUCCEEDED
        || !"CONFIRMED".equals(r.status())
        || blocked)
      throw new ConflictException("APPOINTMENT_COMPLETE_UNAVAILABLE", "当前预约未付款、已释放/完成，或存在待处理售后/核查");
    db.update(
        "insert into appointment_complete_key(merchant_id,idempotency_key,order_id,payload_hash)"
            + " values(?,?,?,?)",
        m,
        k,
        id,
        h);
    db.update(
        "update appointment_reservation set status='FULFILLED',fulfilled_at=?,version=version+1"
            + " where order_id=?",
        Timestamp.from(Instant.now()),
        id);
    store.event(r, id, r.version() + 1, "APPOINTMENT_TEST_FULFILLED");
    return mapper.toResponse(order);
  }
}
