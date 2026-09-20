package com.megagraph.shop.payment;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentChannelEventRepository extends JpaRepository<PaymentChannelEvent, Long> {
    Optional<PaymentChannelEvent> findByProviderAndProviderEventId(String provider, String providerEventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from PaymentChannelEvent event where event.provider = :provider and event.providerEventId = :eventId")
    Optional<PaymentChannelEvent> findForUpdate(
            @Param("provider") String provider,
            @Param("eventId") String eventId
    );
}
