package com.megagraph.shop.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.commerce.RefundGateway;
import com.megagraph.shop.commerce.RefundResult;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "test"})
public class MockChannelCommandJob {

    private final MockCommandTransactionService transactions;
    private final PaymentCallbackIngress callbacks;
    private final MockPaymentCallbackAdapter mockAdapter;
    private final RefundGateway refundGateway;
    private final ObjectMapper objectMapper;

    public MockChannelCommandJob(
            MockCommandTransactionService transactions,
            PaymentCallbackIngress callbacks,
            MockPaymentCallbackAdapter mockAdapter,
            RefundGateway refundGateway,
            ObjectMapper objectMapper
    ) {
        this.transactions = transactions;
        this.callbacks = callbacks;
        this.mockAdapter = mockAdapter;
        this.refundGateway = refundGateway;
        this.objectMapper = objectMapper;
    }

    @Scheduled(
            fixedDelayString = "${app.payments.mock.command-scan-ms:250}",
            initialDelayString = "${app.payments.mock.command-initial-delay-ms:250}"
    )
    public void processScheduled() {
        processAvailable(20);
    }

    public int processAvailable(int limit) {
        int processed = 0;
        while (processed < limit) {
            MockCommandSnapshot command = transactions.claimNext();
            if (command == null) {
                return processed;
            }
            try {
                execute(command);
                transactions.complete(command.id());
            } catch (Exception exception) {
                transactions.fail(command.id(), exception);
            }
            processed += 1;
        }
        return processed;
    }

    private void execute(MockCommandSnapshot command) throws Exception {
        String providerResourceId = command.providerResourceId();
        if (command.resourceType() == ChannelResourceType.REFUND) {
            RefundSubmission submission = transactions.refundSubmission(command.refundOrderId());
            if (submission.providerRefundId() == null) {
                RefundResult accepted = refundGateway.refund(
                        submission.providerPaymentId(),
                        submission.amount(),
                        submission.currency(),
                        command.providerEventId()
                );
                providerResourceId = accepted.providerRefundId();
            } else {
                providerResourceId = submission.providerRefundId();
            }
            transactions.bindRefundResource(command.id(), providerResourceId);
            send(command, command.providerEventId() + "-processing", ChannelEventType.REFUND_PROCESSING, providerResourceId);
        }
        send(command, command.providerEventId(), command.eventType(), providerResourceId);
    }

    private void send(
            MockCommandSnapshot command,
            String eventId,
            ChannelEventType eventType,
            String providerResourceId
    ) throws Exception {
        MockPaymentCallbackAdapter.MockEventPayload payload =
                new MockPaymentCallbackAdapter.MockEventPayload(
                        eventId,
                        eventType,
                        command.resourceType(),
                        providerResourceId,
                        command.occurredAt(),
                        command.amount(),
                        command.currency()
                );
        byte[] rawBody = objectMapper.writeValueAsBytes(payload);
        HttpHeaders headers = new HttpHeaders();
        headers.set(MockPaymentCallbackAdapter.SIGNATURE_HEADER, mockAdapter.sign(rawBody));
        callbacks.receive(command.provider(), headers, rawBody);
    }
}
