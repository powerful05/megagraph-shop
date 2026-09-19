package com.megagraph.shop.security;

import com.megagraph.shop.identity.MerchantRole;
import com.megagraph.shop.identity.MerchantUser;
import com.megagraph.shop.identity.MerchantUserRepository;
import com.megagraph.shop.identity.UserAccount;
import com.megagraph.shop.identity.UserAccountRepository;
import com.megagraph.shop.shared.ForbiddenException;
import java.util.Arrays;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class CurrentMerchant {

    private final UserAccountRepository users;
    private final MerchantUserRepository memberships;

    public CurrentMerchant(UserAccountRepository users, MerchantUserRepository memberships) {
        this.users = users;
        this.memberships = memberships;
    }

    public MerchantActor requireActor(Authentication authentication, MerchantRole... allowedRoles) {
        UserAccount account = users.findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ForbiddenException("ACCOUNT_NOT_FOUND", "登录账号不存在"));
        MerchantUser membership = memberships.findFirstByUserIdOrderByIdAsc(account.getId())
                .orElseThrow(() -> new ForbiddenException("MERCHANT_MEMBERSHIP_REQUIRED", "账号未加入商家"));

        if (allowedRoles.length > 0 && Arrays.stream(allowedRoles).noneMatch(role -> role == membership.getRole())) {
            throw new ForbiddenException("MERCHANT_ROLE_FORBIDDEN", "当前角色无权执行该操作");
        }
        return new MerchantActor(account.getId(), membership.getMerchantId(), membership.getRole());
    }
}

