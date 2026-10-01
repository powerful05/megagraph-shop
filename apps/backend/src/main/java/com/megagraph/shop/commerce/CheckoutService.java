package com.megagraph.shop.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.api.CheckoutGroupResponse;
import com.megagraph.shop.api.CreateOrderRequest;
import com.megagraph.shop.catalog.ProductSkuRepository;
import com.megagraph.shop.projection.OutboxEvent;
import com.megagraph.shop.projection.OutboxEventRepository;
import com.megagraph.shop.shared.ConflictException;
import com.megagraph.shop.shared.NotFoundException;
import com.megagraph.shop.shared.ValidationException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckoutService {
    private final ShoppingCartRepository carts;
    private final ShoppingCartItemRepository items;
    private final ProductSkuRepository skus;
    private final CheckoutGroupRepository groups;
    private final SalesOrderRepository orders;
    private final OrderService orderService;
    private final OrderMapper mapper;
    private final CartService cartService;
    private final ObjectMapper json;
    private final OutboxEventRepository outbox;

    public CheckoutService(ShoppingCartRepository carts, ShoppingCartItemRepository items, ProductSkuRepository skus,
            CheckoutGroupRepository groups, SalesOrderRepository orders, OrderService orderService, OrderMapper mapper,
            CartService cartService, ObjectMapper json, OutboxEventRepository outbox) {
        this.carts = carts; this.items = items; this.skus = skus; this.groups = groups; this.orders = orders;
        this.orderService = orderService; this.mapper = mapper; this.cartService = cartService;
        this.json = json; this.outbox = outbox;
    }

    @Transactional
    public CheckoutGroupResponse create(Long userId, String key, CreateOrderRequest request) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ValidationException("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 不能为空且不能超过 128 个字符");
        }
        CreateOrderRequest normalized = new CreateOrderRequest(request.recipientName().trim(), request.recipientPhone().trim(),
                request.shippingAddress().trim());
        String fingerprint = fingerprint(normalized);
        // Serialize cart edits and checkout before reading the idempotency record.
        ShoppingCart cart = carts.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ValidationException("CART_EMPTY", "购物车为空"));
        CheckoutGroup existing = groups.findByUserIdAndIdempotencyKey(userId, key.trim()).orElse(null);
        if (existing != null) {
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw new ConflictException("CHECKOUT_IDEMPOTENCY_CONFLICT", "同一幂等键不能用于不同的收货信息");
            }
            return response(existing);
        }
        List<ShoppingCartItem> requested = items.findByCartIdOrderByIdAsc(cart.getId());
        if (requested.isEmpty()) throw new ValidationException("CART_EMPTY", "购物车为空");
        var locked = skus.findAllByIdForUpdate(requested.stream().map(ShoppingCartItem::getSkuId).distinct().sorted().toList());
        Map<Long, Long> merchantBySku = new LinkedHashMap<>();
        locked.forEach(sku -> merchantBySku.put(sku.getId(), sku.getMerchantId()));
        if (locked.size() != requested.size()) throw new NotFoundException("CART_SKU_NOT_FOUND", "购物车中的 SKU 不存在");
        if (locked.stream().anyMatch(sku -> !"CNY".equals(sku.getCurrency()))) {
            throw new ValidationException("CHECKOUT_CURRENCY_UNSUPPORTED", "本阶段跨商家结算仅支持 CNY");
        }
        var snapshot = cartService.get(userId);
        CheckoutGroup group = groups.saveAndFlush(new CheckoutGroup(userId, key.trim(), fingerprint, "CNY", snapshot.estimatedTotal()));
        for (Long merchant : merchantBySku.values().stream().distinct().sorted().toList()) {
            List<ShoppingCartItem> childItems = requested.stream().filter(item -> merchantBySku.get(item.getSkuId()).equals(merchant)).toList();
            orderService.createForItems(userId, "CHECKOUT-" + group.getId() + "-" + merchant, normalized, childItems, group.getId(), true);
        }
        CheckoutGroupResponse response = response(group);
        try {
            outbox.save(new OutboxEvent("CHECKOUT", group.getId(), 0L, "CHECKOUT_CREATED", json.writeValueAsString(Map.of(
                    "checkoutGroupId", group.getId(), "orderIds", response.orders().stream().map(order -> order.id()).toList(),
                    "totalAmount", group.getTotalAmount(), "currency", group.getCurrency()))));
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot serialize checkout event", exception); }
        return response;
    }

    @Transactional(readOnly = true)
    public CheckoutGroupResponse get(Long userId, Long id) {
        return response(groups.findByIdAndUserId(id, userId).orElseThrow(() -> new NotFoundException("CHECKOUT_NOT_FOUND", "下单组不存在")));
    }

    private CheckoutGroupResponse response(CheckoutGroup group) {
        return new CheckoutGroupResponse(group.getId(), group.getGroupNo(), group.getCurrency(), group.getTotalAmount(),
                group.getCreatedAt(), orders.findByCheckoutGroupIdOrderByIdAsc(group.getId()).stream().map(mapper::toResponse).toList());
    }

    private String fingerprint(CreateOrderRequest request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request))); }
        catch (JsonProcessingException | NoSuchAlgorithmException exception) { throw new IllegalStateException("Cannot fingerprint checkout", exception); }
    }
}
