package com.megagraph.shop.commerce;

import java.math.BigDecimal;

public interface PaymentGateway {
    PaymentSession createPayment(String orderNo, BigDecimal amount, String currency);
}
