package com.megagraph.shop.guide;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuideConversationRepository extends JpaRepository<GuideConversation, Long> {
    List<GuideConversation> findTop50ByOwnerUserIdOrderByUpdatedAtDescIdDesc(Long ownerUserId);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from GuideConversation c where c.publicId = :id and c.ownerUserId = :owner")
    Optional<GuideConversation> ownedForUpdate(@Param("id") String id, @Param("owner") Long owner);
}
