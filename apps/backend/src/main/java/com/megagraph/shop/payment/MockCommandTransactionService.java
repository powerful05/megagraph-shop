package com.megagraph.shop.payment;

import com.megagraph.shop.commerce.PaymentOrder;
import com.megagraph.shop.commerce.PaymentOrderRepository;
import com.megagraph.shop.commerce.RefundOrder;
import com.megagraph.shop.commerce.RefundOrderRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MockCommandTransactionService {

    private final PaymentChannelCommandRepository commands;
    private final RefundOrderRepository refunds;
    private final PaymentOrderRepository payments;

    public MockCommandTransactionService(
            PaymentChannelCommandRepository commands,
            RefundOrderRepository refunds,
            PaymentOrderRepository payments
    ) {
        this.commands = commands;
        this.refunds = refunds;
        this.payments = payments;
    }

    @Transactional
    public MockCommandSnapshot claimNext() {
        Instant now = Instant.now();
        commands.findFirstByStatusAndClaimedAtBeforeOrderByClaimedAtAscIdAsc(
                        ChannelCommandStatus.PROCESSING,
                        now.minus(Duration.ofSeconds(30))
                )
                .ifPresent(value -> value.recover(now));
        PaymentChannelCommand command = commands
                .findFirstByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
                        ChannelCommandStatus.PENDING,
                        now
                )
                .orElse(null);
        if (command == null) {
            return null;
        }
        command.claim();
        commands.flush();
        return snapshot(command);
    }

    @Transactional(readOnly = true)
    public RefundSubmission refundSubmission(Long refundOrderId) {
        RefundOrder refund = refunds.findById(refundOrderId)
                .orElseThrow(() -> new IllegalStateException("Refund command has no refund order"));
        PaymentOrder payment = payments.findById(refund.getPaymentOrderId())
                .orElseThrow(() -> new IllegalStateException("Refund command has no payment order"));
        return new RefundSubmission(
                payment.getProviderPaymentId(),
                refund.getProviderRefundId(),
                refund.getAmount(),
                refund.getCurrency()
        );
    }

    @Transactional
    public void bindRefundResource(Long commandId, String providerRefundId) {
        PaymentChannelCommand command = requireCommand(commandId);
        RefundOrder refund = refunds.findByIdForUpdate(command.getRefundOrderId())
                .orElseThrow(() -> new IllegalStateException("Refund command has no refund order"));
        refund.bindProviderRefundId(providerRefundId);
        command.bindProviderResourceId(providerRefundId);
        refunds.flush();
        commands.flush();
    }

    @Transactional
    public void complete(Long commandId) {
        requireCommand(commandId).complete();
    }

    @Transactional
    public void fail(Long commandId, Exception exception) {
        requireCommand(commandId).retry(exception.getMessage(), Instant.now().plus(Duration.ofSeconds(2)), 5);
    }

    private PaymentChannelCommand requireCommand(Long commandId) {
        return commands.findByIdForUpdate(commandId)
                .orElseThrow(() -> new IllegalStateException("Mock command no longer exists"));
    }

    private MockCommandSnapshot snapshot(PaymentChannelCommand command) {
        return new MockCommandSnapshot(
                command.getId(),
                command.getProvider(),
                command.getProviderEventId(),
                command.getEventType(),
                command.getResourceType(),
                command.getProviderResourceId(),
                command.getRefundOrderId(),
                command.getAmount(),
                command.getCurrency(),
                command.getCreatedAt()
        );
    }
}
