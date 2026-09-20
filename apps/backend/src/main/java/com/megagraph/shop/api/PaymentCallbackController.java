package com.megagraph.shop.api;

import com.megagraph.shop.payment.CallbackIngressResult;
import com.megagraph.shop.payment.PaymentCallbackIngress;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payment-callbacks")
public class PaymentCallbackController {

    private final PaymentCallbackIngress ingress;

    public PaymentCallbackController(PaymentCallbackIngress ingress) {
        this.ingress = ingress;
    }

    @PostMapping("/{provider}")
    PaymentCallbackResponse receive(
            @PathVariable String provider,
            @RequestHeader HttpHeaders headers,
            @RequestBody byte[] rawBody
    ) {
        CallbackIngressResult received = ingress.receive(provider, headers, rawBody);
        return new PaymentCallbackResponse(
                received.result().provider(),
                received.result().providerEventId(),
                received.result().status().name(),
                received.duplicate()
        );
    }
}
