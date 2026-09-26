package com.megagraph.shop.identity;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrialInviteRepository extends JpaRepository<TrialInvite, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select invite from TrialInvite invite where invite.codeHash = :hash")
    Optional<TrialInvite> findByCodeHashForUpdate(@Param("hash") String hash);
}
