package com.megagraph.shop.commerce;

import java.math.BigDecimal;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !test")
public class UnavailablePaymentGateway implements PaymentGateway {
    @Override
    public PaymentSession createPayment(String orderNo, BigDecimal amount, String currency) {
        throw new IllegalStateException("No production payment gateway is configured");
    }
}
