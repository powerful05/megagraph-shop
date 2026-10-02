package com.megagraph.shop.api;

import com.megagraph.shop.delivery.AppointmentService;
import com.megagraph.shop.identity.MerchantRole;
import com.megagraph.shop.security.CurrentMerchant;
import com.megagraph.shop.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class AppointmentController {
  private final AppointmentService service;
  private final CurrentMerchant merchants;
  private final CurrentUser users;

  public AppointmentController(
      AppointmentService service, CurrentMerchant merchants, CurrentUser users) {
    this.service = service;
    this.merchants = merchants;
    this.users = users;
  }

  @GetMapping("/api/merchant/v1/appointment-offers")
  List<AppointmentOfferResponse> list(Authentication auth) {
    return service.offers(merchants.requireActor(auth).merchantId());
  }

  @PostMapping("/api/merchant/v1/appointment-offers")
  AppointmentOfferResponse create(
      Authentication auth,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody AppointmentOfferRequest r) {
    return service.create(
        merchants.requireActor(auth, MerchantRole.OWNER, MerchantRole.EDITOR).merchantId(),
        key,
        r.internal());
  }

  @PatchMapping("/api/merchant/v1/appointment-offers/{id}")
  AppointmentOfferResponse edit(
      Authentication auth, @PathVariable Long id, @Valid @RequestBody AppointmentOfferRequest r) {
    return service.edit(
        merchants.requireActor(auth, MerchantRole.OWNER, MerchantRole.EDITOR).merchantId(),
        id,
        r.internal());
  }

  @PostMapping("/api/merchant/v1/appointment-offers/{id}/confirm")
  AppointmentOfferResponse confirm(
      Authentication auth, @PathVariable Long id, @Valid @RequestBody AppointmentVersionRequest r) {
    return service.transition(
        merchants.requireActor(auth, MerchantRole.OWNER, MerchantRole.EDITOR).merchantId(),
        id,
        r.version(),
        "confirm");
  }

  @PostMapping("/api/merchant/v1/appointment-offers/{id}/publish")
  AppointmentOfferResponse publish(
      Authentication auth, @PathVariable Long id, @Valid @RequestBody AppointmentVersionRequest r) {
    return service.transition(
        merchants.requireActor(auth, MerchantRole.OWNER, MerchantRole.PUBLISHER).merchantId(),
        id,
        r.version(),
        "publish");
  }

  @PostMapping("/api/merchant/v1/appointment-offers/{id}/off-shelf")
  AppointmentOfferResponse offShelf(
      Authentication auth, @PathVariable Long id, @Valid @RequestBody AppointmentVersionRequest r) {
    return service.transition(
        merchants.requireActor(auth, MerchantRole.OWNER, MerchantRole.PUBLISHER).merchantId(),
        id,
        r.version(),
        "off-shelf");
  }

  @GetMapping("/api/shop/v1/appointment-offers")
  List<AppointmentOfferResponse> publicOffers() {
    return service.publicOffers();
  }

  @GetMapping("/api/shop/v1/appointment-offers/{id}")
  AppointmentOfferResponse offer(@PathVariable Long id) {
    return service.publicOffer(id);
  }

  @PostMapping("/api/shop/v1/appointment-orders")
  OrderResponse buy(
      Authentication auth,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody AppointmentOrderRequest r) {
    return service.purchase(users.requireAccount(auth).getId(), key, r);
  }

  @PostMapping("/api/merchant/v1/appointment-offers/{id}/slots")
  AppointmentOfferResponse addSlot(
      Authentication auth,
      @PathVariable Long id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody AppointmentSlotRequest r) {
    return service.addSlot(
        merchants.requireActor(auth, MerchantRole.OWNER, MerchantRole.EDITOR).merchantId(),
        id,
        key,
        r);
  }

  @PostMapping("/api/merchant/v1/appointment-offers/{id}/slots/{slotId}/close")
  AppointmentOfferResponse closeSlot(
      Authentication auth,
      @PathVariable Long id,
      @PathVariable Long slotId,
      @Valid @RequestBody AppointmentVersionRequest r) {
    return service.closeSlot(
        merchants.requireActor(auth, MerchantRole.OWNER, MerchantRole.EDITOR).merchantId(),
        id,
        slotId,
        r.version());
  }

  @PostMapping("/api/merchant/v1/orders/{id}/appointment-complete")
  OrderResponse complete(
      Authentication auth,
      @PathVariable Long id,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody AppointmentVersionRequest r) {
    return service.complete(
        merchants.requireActor(auth, MerchantRole.OWNER).merchantId(), id, key, r.version());
  }
}
