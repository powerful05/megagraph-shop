package com.megagraph.shop.security;

import com.megagraph.shop.identity.MerchantRole;
import com.megagraph.shop.identity.MerchantRepository;
import com.megagraph.shop.identity.MerchantStatus;
import com.megagraph.shop.identity.MerchantUser;
import com.megagraph.shop.identity.MerchantUserRepository;
import com.megagraph.shop.identity.UserAccount;
import com.megagraph.shop.identity.UserAccountRepository;
import com.megagraph.shop.identity.UserStatus;
import com.megagraph.shop.shared.DomainException;
import com.megagraph.shop.shared.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class CurrentMerchant {

    private final UserAccountRepository users;
    private final MerchantUserRepository memberships;
    private final MerchantRepository merchants;
    private final HttpServletRequest request;
    private final boolean allowLegacyContext;

    public CurrentMerchant(
            UserAccountRepository users,
            MerchantUserRepository memberships,
            MerchantRepository merchants,
            HttpServletRequest request,
            @Value("${app.security.legacy-merchant-context-enabled:false}") boolean allowLegacyContext
    ) {
        this.users = users;
        this.memberships = memberships;
        this.merchants = merchants;
        this.request = request;
        this.allowLegacyContext = allowLegacyContext;
    }

    public MerchantActor requireActor(Authentication authentication, MerchantRole... allowedRoles) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "请先登录");
        }
        UserAccount account = users.findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ForbiddenException("ACCOUNT_NOT_FOUND", "登录账号不存在"));
        if (account.getStatus() != UserStatus.ACTIVE) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "请重新登录");
        }
        String header = request.getHeader("X-Merchant-Id");
        MerchantUser membership;
        if ((header == null || header.isBlank()) && allowLegacyContext) {
            membership = memberships.findFirstByUserIdOrderByIdAsc(account.getId())
                    .orElseThrow(() -> new ForbiddenException("MERCHANT_MEMBERSHIP_REQUIRED", "账号未加入商家"));
        } else {
            if (header == null || !header.matches("[1-9][0-9]*")) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "MERCHANT_CONTEXT_REQUIRED", "请选择商家");
            }
            Long merchantId;
            try {
                merchantId = Long.valueOf(header);
            } catch (NumberFormatException exception) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "MERCHANT_CONTEXT_REQUIRED", "商家编号无效");
            }
            membership = memberships.findByMerchantIdAndUserId(merchantId, account.getId())
                    .orElseThrow(() -> new ForbiddenException("MERCHANT_ACCESS_DENIED", "无权访问该商家"));
        }

        boolean activeMerchant = merchants.findById(membership.getMerchantId())
                .map(merchant -> merchant.getStatus() == MerchantStatus.ACTIVE)
                .orElse(false);
        if (!activeMerchant) {
            throw new ForbiddenException("MERCHANT_ACCESS_DENIED", "商家当前不可用");
        }

        if (allowedRoles.length > 0 && Arrays.stream(allowedRoles).noneMatch(role -> role == membership.getRole())) {
            throw new ForbiddenException("MERCHANT_ROLE_FORBIDDEN", "当前角色无权执行该操作");
        }
        return new MerchantActor(account.getId(), membership.getMerchantId(), membership.getRole());
    }
}
