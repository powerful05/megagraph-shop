package com.megagraph.shop.delivery;

import com.megagraph.shop.api.*;
import com.megagraph.shop.commerce.*;
import com.megagraph.shop.payment.CommerceOutboxWriter;
import com.megagraph.shop.shared.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DigitalDeliveryService {
  private final JdbcTemplate db;
  private final DigitalDeliveryStore store;
  private final SalesOrderRepository orders;
  private final PaymentOrderRepository payments;
  private final PaymentGateway gateway;
  private final OrderMapper mapper;
  private final CommerceOutboxWriter outbox;
  private final Environment environment;

  public DigitalDeliveryService(
      JdbcTemplate db,
      DigitalDeliveryStore store,
      SalesOrderRepository orders,
      PaymentOrderRepository payments,
      PaymentGateway gateway,
      OrderMapper mapper,
      CommerceOutboxWriter outbox,
      Environment environment) {
    this.db = db;
    this.store = store;
    this.orders = orders;
    this.payments = payments;
    this.gateway = gateway;
    this.mapper = mapper;
    this.outbox = outbox;
    this.environment = environment;
  }

  public boolean available() {
    return environment.acceptsProfiles(Profiles.of("test"))
        || environment.acceptsProfiles(Profiles.of("local"))
            && environment.acceptsProfiles(Profiles.of("supply-demo"));
  }

  private void requireDemo() {
    if (!available())
      throw new DomainException(
          HttpStatus.SERVICE_UNAVAILABLE, "DIGITAL_DEMO_UNAVAILABLE", "当前环境未开放数字文件测试交付");
  }

  public List<DeliveryOfferResponse> list(Long merchant) {
    requireDemo();
    return store.offers(merchant);
  }

  public List<DeliveryOfferResponse> publicOffers() {
    return available() ? store.publicOffers() : List.of();
  }

  public DeliveryOfferResponse get(Long id) {
    requireDemo();
    return store.publicOffer(id);
  }

  private String key(String s) {
    if (s == null || s.isBlank() || s.strip().length() > 128)
      throw new ValidationException("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key不能为空且不超过128字符");
    return s.strip();
  }

  private void valid(DeliveryOfferRequest r, boolean create) {
    if ((create && !"DIGITAL_FILE".equals(r.deliveryType()))
        || r.deliveryType() != null && !"DIGITAL_FILE".equals(r.deliveryType())
        || !"CNY".equals(r.currency())
        || r.title() == null
        || !r.title().contains("测试")
        || r.description() == null
        || !r.description().contains("测试")
        || r.price() == null
        || r.price().signum() <= 0
        || r.price().scale() > 2
        || r.price().precision() - r.price().scale() > 10)
      throw new ValidationException("DELIVERY_OFFER_INVALID", "仅支持标题及描述标明测试、正价CNY的自建测试数字文件");
  }

  private String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String canonical(String... fields) {
    StringBuilder b = new StringBuilder();
    for (String f : fields) b.append(f.length()).append(':').append(f);
    return b.toString();
  }

  private Long generated(String sql, Object... args) {
    var kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
    db.update(
        con -> {
          var ps = con.prepareStatement(sql, new String[] {"id"});
          for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
          return ps;
        },
        kh);
    return kh.getKey().longValue();
  }

  private Long asset(Long merchant, long version, DeliveryOfferRequest r) {
    String content =
        "MegaGraph 自建测试文件\n商家 "
            + merchant
            + "\n版本 "
            + version
            + "\n"
            + r.title().strip()
            + "\n"
            + r.description().strip()
            + "\n仅用于本地受控 Demo，非真实服务或实物。\n";
    return generated(
        "insert into"
            + " digital_asset(merchant_id,asset_version,content_hash,content_type,content_text)"
            + " values(?,?,?,'text/plain;charset=UTF-8',?)",
        merchant,
        version,
        hash(content),
        content);
  }

  @Transactional
  public DeliveryOfferResponse create(Long merchant, String idem, DeliveryOfferRequest r) {
    requireDemo();
    valid(r, true);
    String k = key(idem),
        h =
            hash(
                canonical(
                    r.title().strip(),
                    r.description().strip(),
                    r.price().stripTrailingZeros().toPlainString(),
                    "CNY"));
    db.queryForObject("select id from merchant where id=? for update", Long.class, merchant);
    var existing =
        db.queryForList(
            "select id,payload_hash from delivery_offer where merchant_id=? and idempotency_key=?",
            merchant,
            k);
    if (!existing.isEmpty()) {
      if (!h.equals(existing.getFirst().get("payload_hash")))
        throw new ConflictException("DELIVERY_IDEMPOTENCY_CONFLICT", "该键已用于不同数字文件资料");
      return store.merchantOffer(merchant, ((Number) existing.getFirst().get("id")).longValue());
    }
    Long a = asset(merchant, 1, r);
    Long id =
        generated(
            "insert into"
                + " delivery_offer(merchant_id,delivery_type,title,description,price,currency,status,version,asset_id,idempotency_key,payload_hash)"
                + " values(?,'DIGITAL_FILE',?,?,?,'CNY','DRAFT',0,?,?,?)",
            merchant,
            r.title().strip(),
            r.description().strip(),
            r.price(),
            a,
            k,
            h);
    event(id, 0L, "DELIVERY_OFFER_CREATED");
    return store.merchantOffer(merchant, id);
  }

  @Transactional
  public DeliveryOfferResponse edit(Long merchant, Long id, DeliveryOfferRequest r) {
    requireDemo();
    valid(r, false);
    var o = lockOffer(merchant, id, r.version());
    Long a = asset(merchant, o.assetVersion() + 1, r);
    db.update(
        "update delivery_offer set"
            + " title=?,description=?,price=?,asset_id=?,version=version+1,status='DRAFT',confirmed_version=null"
            + " where id=?",
        r.title().strip(),
        r.description().strip(),
        r.price(),
        a,
        id);
    event(id, o.version() + 1, "DELIVERY_OFFER_EDITED");
    return store.merchantOffer(merchant, id);
  }

  private DeliveryOfferResponse lockOffer(Long merchant, Long id, Long version) {
    db
        .query(
            "select id from delivery_offer where id=? and merchant_id=? for update",
            (r, n) -> r.getLong(1),
            id,
            merchant)
        .stream()
        .findFirst()
        .orElseThrow(() -> new NotFoundException("DELIVERY_OFFER_NOT_FOUND", "数字文件不存在"));
    var o = store.currentMerchantOffer(merchant, id);
    if (!Objects.equals(o.version(), version))
      throw new ConflictException("DELIVERY_VERSION_CONFLICT", "数字文件已更新，请刷新后重试");
    return o;
  }

  @Transactional
  public DeliveryOfferResponse transition(Long merchant, Long id, Long version, String action) {
    requireDemo();
    var o = lockOffer(merchant, id, version);
    long next = o.version() + 1;
    switch (action) {
      case "confirm" ->
          db.update(
              "update delivery_offer set version=?,confirmed_version=? where id=?", next, next, id);
      case "publish" -> {
        if (!"ACTIVE"
            .equals(
                db.queryForObject(
                    "select status from merchant where id=? for update", String.class, merchant)))
          throw new ConflictException("MERCHANT_INACTIVE", "商家当前不可发布");
        if (!Objects.equals(o.confirmedVersion(), o.version()))
          throw new ConflictException("DELIVERY_CONFIRMATION_REQUIRED", "请先确认当前数字文件版本");
        db.update(
            "update delivery_offer set status='PUBLISHED',version=?,confirmed_version=? where id=?",
            next,
            next,
            id);
      }
      case "off-shelf" ->
          db.update(
              "update delivery_offer set status='OFF_SHELF',version=?,confirmed_version=null where"
                  + " id=?",
              next,
              id);
      default -> throw new IllegalArgumentException();
    }
    event(id, next, "DELIVERY_OFFER_" + action.toUpperCase(Locale.ROOT).replace('-', '_'));
    return store.merchantOffer(merchant, id);
  }

  private void event(Long id, Long version, String event) {
    outbox.save(
        "DELIVERY_OFFER",
        id,
        version,
        event,
        Map.of("offerId", id, "deliveryType", "DIGITAL_FILE", "testOnly", true));
  }

  private Long offerId(DeliveryOrderRequest request) {
    if (request == null || request.items() == null || request.items().size() != 1)
      throw new DomainException(
          HttpStatus.BAD_REQUEST, "DELIVERY_SINGLE_ITEM_REQUIRED", "数字测试购买仅支持一个文件，数量为1，不能混入实物SKU");
    Map<String, Object> item = request.items().getFirst();
    if (item == null
        || !item.keySet().equals(Set.of("offerId", "quantity", "expectedOfferVersion"))
        || !(item.get("offerId") instanceof Number id)
        || !(item.get("quantity") instanceof Number q)
        || !(item.get("expectedOfferVersion") instanceof Number v)
        || new BigDecimal(v.toString()).signum() < 0
        || new BigDecimal(v.toString()).scale() > 0
        || new BigDecimal(q.toString()).compareTo(BigDecimal.ONE) != 0)
      throw new DomainException(
          HttpStatus.BAD_REQUEST,
          "DELIVERY_SINGLE_ITEM_REQUIRED",
          "仅接受 offerId 和数量1；不能混入SKU或客户端价格");
    try {
      return new BigDecimal(id.toString()).longValueExact();
    } catch (ArithmeticException e) {
      throw new DomainException(HttpStatus.BAD_REQUEST, "DELIVERY_OFFER_INVALID", "offerId必须是整数");
    }
  }

  @Transactional
  public OrderResponse purchase(Long user, String idem, DeliveryOrderRequest request) {
    requireDemo();
    Long offer = offerId(request);
    long expected;
    try {
      expected =
          new BigDecimal(request.items().getFirst().get("expectedOfferVersion").toString())
              .longValueExact();
    } catch (ArithmeticException e) {
      throw new DomainException(
          HttpStatus.BAD_REQUEST, "DELIVERY_VERSION_INVALID", "expectedOfferVersion必须是非负long整数");
    }
    String k = key(idem), h = hash("DIGITAL_FILE:" + offer + ":1:" + expected);
    db.queryForObject("select id from app_user where id=? for update", Long.class, user);
    var existing = orders.findByUserIdAndIdempotencyKey(user, k).orElse(null);
    if (existing != null) {
      var hashes =
          db.queryForList(
              "select payload_hash from delivery_purchase where order_id=?",
              String.class,
              existing.getId());
      if (hashes.isEmpty() || !h.equals(hashes.getFirst()))
        throw new ConflictException("DELIVERY_IDEMPOTENCY_CONFLICT", "该购买键已用于不同订单");
      return mapper.toResponse(existing);
    }
    db.query("select id from delivery_offer where id=? for update", (r, n) -> r.getLong(1), offer);
    var o = store.currentPublicOffer(offer);
    if (o.version() != expected)
      throw new ConflictException("OFFER_CHANGED", "数字文件资料已更新，请重新查看并确认价格及交付约定");
    Long asset =
        db.queryForObject(
            "select asset_id from delivery_offer where id=? for update", Long.class, offer);
    SalesOrder order =
        new SalesOrder(
            "MGD"
                + UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .substring(0, 23)
                    .toUpperCase(Locale.ROOT),
            user,
            o.merchantId(),
            k,
            "CNY",
            o.price(),
            "数字文件测试交付",
            "不适用",
            "数字权益领取",
            Instant.now().plus(Duration.ofMinutes(15)));
    order.markDigital();
    order = orders.saveAndFlush(order);
    db.update(
        "insert into"
            + " delivery_purchase(order_id,offer_id,offer_version,title,description,unit_price,currency,asset_id,asset_version,asset_hash,delivery_terms,refund_policy,availability_hours,payload_hash)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,24,?)",
        order.getId(),
        o.id(),
        o.version(),
        o.title(),
        o.description(),
        o.price(),
        o.currency(),
        asset,
        o.assetVersion(),
        o.assetHash(),
        o.deliveryTerms(),
        o.refundPolicy(),
        h);
    PaymentSession session = gateway.createPayment(order.getOrderNo(), o.price(), "CNY");
    payments.saveAndFlush(
        new PaymentOrder(
            order.getId(), session.provider(), session.providerPaymentId(), o.price(), "CNY"));
    outbox.save(
        "ORDER",
        order.getId(),
        order.getVersion(),
        "ORDER_CREATED",
        Map.of(
            "orderId",
            order.getId(),
            "deliveryType",
            "DIGITAL_FILE",
            "offerId",
            offer,
            "testOrder",
            true));
    return mapper.toResponse(order);
  }

  private SalesOrder ownedLocked(Long user, Long id) {
    return orders
        .findByIdForUpdate(id)
        .filter(o -> o.getUserId().equals(user) && "DIGITAL_FILE".equals(o.getDeliveryType()))
        .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "数字订单不存在"));
  }

  private DigitalDeliveryResponse.Entitlement eligible(SalesOrder order, boolean download) {
    var e = store.currentEntitlement(order.getId());
    var p = payments.findByOrderIdForUpdate(order.getId()).orElse(null);
    boolean blocked =
        !db.queryForList(
                "select id from after_sale_request where order_id=? and status in"
                    + " ('REQUESTED','REFUND_PROCESSING','REFUND_FAILED','REFUNDED') for update",
                Long.class,
                order.getId())
            .isEmpty();
    if (order.getStatus() != OrderStatus.PAID
        || p == null
        || p.getStatus() != PaymentStatus.SUCCEEDED
        || blocked
        || e == null
        || "REVOKED".equals(e.status())
        || !Instant.now().isBefore(e.expiresAt())
        || download && !"CLAIMED".equals(e.status()))
      throw new ConflictException(
          "DIGITAL_ENTITLEMENT_UNAVAILABLE", "文件未支付、未领取、已到期或退款处理中，当前不能领取或下载");
    return e;
  }

  @Transactional
  public OrderResponse claim(Long user, Long id, String idem) {
    requireDemo();
    String k = key(idem);
    db.queryForObject("select id from app_user where id=? for update", Long.class, user);
    SalesOrder order = ownedLocked(user, id);
    var e = eligible(order, false);
    var keys =
        db.queryForList(
            "select order_id from digital_claim_key where user_id=? and idempotency_key=?",
            Long.class,
            user,
            k);
    if (!keys.isEmpty() && !id.equals(keys.getFirst()))
      throw new ConflictException("DIGITAL_CLAIM_IDEMPOTENCY_CONFLICT", "领取键已用于其他订单");
    if (keys.isEmpty())
      db.update(
          "insert into digital_claim_key(user_id,idempotency_key,order_id) values(?,?,?)",
          user,
          k,
          id);
    if ("AVAILABLE".equals(e.status())) {
      db.update(
          "update digital_entitlement set status='CLAIMED',claimed_at=?,version=version+1 where"
              + " order_id=?",
          java.sql.Timestamp.from(Instant.now()),
          id);
      outbox.save(
          "DIGITAL_ENTITLEMENT",
          e.id(),
          1L,
          "DIGITAL_ENTITLEMENT_CLAIMED",
          Map.of("orderId", id, "expiresAt", e.expiresAt()));
    }
    return mapper.toResponse(order);
  }

  @Transactional
  public byte[] download(Long user, Long id) {
    requireDemo();
    var order = ownedLocked(user, id);
    eligible(order, true);
    var rows =
        db.queryForList(
            "select a.content_text,a.content_hash,p.asset_hash from delivery_purchase p join"
                + " digital_asset a on a.id=p.asset_id and a.merchant_id=? where p.order_id=?",
            order.getMerchantId(),
            id);
    if (rows.size() != 1) throw new IllegalStateException("Digital asset snapshot missing");
    var row = rows.getFirst();
    String content = (String) row.get("content_text");
    if (!hash(content).equals(row.get("asset_hash"))
        || !row.get("content_hash").equals(row.get("asset_hash")))
      throw new ConflictException("DIGITAL_ASSET_INTEGRITY_ERROR", "购买快照文件校验失败");
    return content.getBytes(StandardCharsets.UTF_8);
  }
}
