package com.megagraph.shop.api;

import com.megagraph.shop.delivery.DigitalDeliveryService;
import com.megagraph.shop.identity.MerchantRole;
import com.megagraph.shop.security.CurrentMerchant;
import com.megagraph.shop.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class DeliveryController {
  private final DigitalDeliveryService service;
  private final CurrentMerchant merchants;
  private final CurrentUser users;

  public DeliveryController(
      DigitalDeliveryService service, CurrentMerchant merchants, CurrentUser users) {
    this.service = service;
    this.merchants = merchants;
    this.users = users;
  }

  @GetMapping("/api/merchant/v1/delivery-offers")
  List<DeliveryOfferResponse> list(Authentication auth) {
    return service.list(merchants.requireActor(auth).merchantId());
  }

  @PostMapping("/api/merchant/v1/delivery-offers")
  DeliveryOfferResponse create(
      Authentication auth,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody DeliveryOfferRequest r) {
    return service.create(
        merchants.requireActor(auth, MerchantRole.OWNER, MerchantRole.EDITOR).merchantId(), key, r);
  }

  @PatchMapping("/api/merchant/v1/delivery-offers/{id}")
  DeliveryOfferResponse edit(
      Authentication auth, @PathVariable Long id, @Valid @RequestBody DeliveryOfferRequest r) {
    return service.edit(
        merchants.requireActor(auth, MerchantRole.OWNER, MerchantRole.EDITOR).merchantId(), id, r);
  }

  @PostMapping("/api/merchant/v1/delivery-offers/{id}/confirm")
  DeliveryOfferResponse confirm(
      Authentication auth, @PathVariable Long id, @Valid @RequestBody VersionRequest r) {
    return service.transition(
        merchants.requireActor(auth, MerchantRole.OWNER, MerchantRole.EDITOR).merchantId(),
        id,
        r.version(),
        "confirm");
  }

  @PostMapping("/api/merchant/v1/delivery-offers/{id}/publish")
  DeliveryOfferResponse publish(
      Authentication auth, @PathVariable Long id, @Valid @RequestBody VersionRequest r) {
    return service.transition(
        merchants.requireActor(auth, MerchantRole.OWNER, MerchantRole.PUBLISHER).merchantId(),
        id,
        r.version(),
        "publish");
  }

  @PostMapping("/api/merchant/v1/delivery-offers/{id}/off-shelf")
  DeliveryOfferResponse offShelf(
      Authentication auth, @PathVariable Long id, @Valid @RequestBody VersionRequest r) {
    return service.transition(
        merchants.requireActor(auth, MerchantRole.OWNER, MerchantRole.PUBLISHER).merchantId(),
        id,
        r.version(),
        "off-shelf");
  }

  @GetMapping("/api/shop/v1/delivery-offers")
  List<DeliveryOfferResponse> publicOffers() {
    return service.publicOffers();
  }

  @GetMapping("/api/shop/v1/delivery-offers/{id}")
  DeliveryOfferResponse offer(@PathVariable Long id) {
    return service.get(id);
  }

  @PostMapping("/api/shop/v1/delivery-orders")
  OrderResponse buy(
      Authentication auth,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody DeliveryOrderRequest r) {
    return service.purchase(users.requireAccount(auth).getId(), key, r);
  }

  @PostMapping("/api/shop/v1/orders/{id}/digital-claim")
  OrderResponse claim(
      Authentication auth, @PathVariable Long id, @RequestHeader("Idempotency-Key") String key) {
    return service.claim(users.requireAccount(auth).getId(), id, key);
  }

  @GetMapping("/api/shop/v1/orders/{id}/digital-download")
  ResponseEntity<byte[]> download(Authentication auth, @PathVariable Long id) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=megagraph-test-order-" + id + ".txt")
        .header("X-Content-Type-Options", "nosniff")
        .contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
        .body(service.download(users.requireAccount(auth).getId(), id));
  }
}
