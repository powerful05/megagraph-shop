package com.megagraph.shop.guide;

import com.megagraph.shop.api.DeliveryOfferResponse;
import com.megagraph.shop.api.PublicProductResponse;
import java.util.List;

public record GuideChatResult(
    String answer,
    List<PublicProductResponse> products,
    List<String> demoProductIds,
    String advice,
    List<GuideEvidence.Basis> recommendationBasis,
    List<DeliveryOfferResponse> deliveryOffers,
    String responseSource,
    List<com.megagraph.shop.api.AppointmentOfferResponse> appointmentOffers) {
  public GuideChatResult {
    deliveryOffers = deliveryOffers == null ? List.of() : List.copyOf(deliveryOffers);
    appointmentOffers = appointmentOffers == null ? List.of() : List.copyOf(appointmentOffers);
    responseSource = responseSource == null ? "TEXT_MODEL" : responseSource;
  }

  public GuideChatResult(
      String answer,
      List<PublicProductResponse> products,
      List<String> demoProductIds,
      String advice,
      List<GuideEvidence.Basis> basis,
      List<DeliveryOfferResponse> offers,
      String source) {
    this(answer, products, demoProductIds, advice, basis, offers, source, List.of());
  }

  public GuideChatResult(
      String answer,
      List<PublicProductResponse> products,
      List<String> demoProductIds,
      String advice,
      List<GuideEvidence.Basis> basis,
      List<DeliveryOfferResponse> offers) {
    this(answer, products, demoProductIds, advice, basis, offers, "TEXT_MODEL");
  }

  public GuideChatResult(
      String answer,
      List<PublicProductResponse> products,
      List<String> demoProductIds,
      String advice,
      List<GuideEvidence.Basis> basis) {
    this(answer, products, demoProductIds, advice, basis, List.of(), "TEXT_MODEL");
  }

  public GuideChatResult(
      String answer, List<PublicProductResponse> products, List<String> demoProductIds) {
    this(
        answer,
        products,
        demoProductIds,
        "",
        GuideEvidence.defaults(products),
        List.of(),
        "TEXT_MODEL");
  }
}
