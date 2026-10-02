package com.megagraph.shop.guide;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuideTurnRequestRepository extends JpaRepository<GuideTurnRequest, Long> {
    Optional<GuideTurnRequest> findByConversationIdAndRequestId(Long conversationId, String requestId);
    List<GuideTurnRequest> findByConversationIdOrderByTurnSequenceAsc(Long conversationId);
    List<GuideTurnRequest> findByConversationIdAndStatus(Long conversationId, String status);
    List<GuideTurnRequest> findTop9ByConversationIdAndStatusOrderByTurnSequenceDesc(Long conversationId, String status);
}
