package com.megagraph.shop.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.api.ProductEvidence;
import com.megagraph.shop.api.SupplyDetails;
import com.megagraph.shop.identity.MerchantRepository;
import org.springframework.stereotype.Component;

@Component
public class ProductEvidenceMapper {
    private final ObjectMapper json;
    private final MerchantRepository merchants;

    public ProductEvidenceMapper(ObjectMapper json, MerchantRepository merchants) {
        this.json = json;
        this.merchants = merchants;
    }

    public ProductEvidence map(Product product) {
        SupplyDetails details = null;
        if (product.getSupplyDetails() != null) {
            try {
                details = json.readValue(product.getSupplyDetails(), SupplyDetails.class);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Invalid stored product supply details", exception);
            }
        }
        String status = details == null ? "MISSING" : product.getDraftStatus() != DraftStatus.CONFIRMED
                ? "UNCONFIRMED" : product.isTestSupply() ? "TEST_CONFIRMED" : "MERCHANT_CONFIRMED";
        return new ProductEvidence(product.getVersion(), product.getMerchantId(),
                merchants.findById(product.getMerchantId()).map(merchant -> merchant.getName()).orElse(""),
                "PHYSICAL", product.isTestSupply() ? "LOCAL_TEST" : "MERCHANT", status,
                product.getConfirmedAt(), details);
    }
}
