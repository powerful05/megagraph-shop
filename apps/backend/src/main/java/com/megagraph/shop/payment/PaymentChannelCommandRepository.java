package com.megagraph.shop.payment;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentChannelCommandRepository extends JpaRepository<PaymentChannelCommand, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentChannelCommand> findFirstByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAscIdAsc(
            ChannelCommandStatus status,
            Instant now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentChannelCommand> findFirstByStatusAndClaimedAtBeforeOrderByClaimedAtAscIdAsc(
            ChannelCommandStatus status,
            Instant before
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select command from PaymentChannelCommand command where command.id = :id")
    Optional<PaymentChannelCommand> findByIdForUpdate(@Param("id") Long id);
}
