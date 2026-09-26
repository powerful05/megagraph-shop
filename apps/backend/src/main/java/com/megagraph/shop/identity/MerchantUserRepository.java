package com.megagraph.shop.identity;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantUserRepository extends JpaRepository<MerchantUser, Long> {
    List<MerchantUser> findByUserId(Long userId);
    Optional<MerchantUser> findFirstByUserIdOrderByIdAsc(Long userId);
    Optional<MerchantUser> findByMerchantIdAndUserId(Long merchantId, Long userId);
}
