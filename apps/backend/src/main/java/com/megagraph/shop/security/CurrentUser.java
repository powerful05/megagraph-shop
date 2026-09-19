package com.megagraph.shop.security;

import com.megagraph.shop.identity.UserAccount;
import com.megagraph.shop.identity.UserAccountRepository;
import com.megagraph.shop.shared.ForbiddenException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {

    private final UserAccountRepository users;

    public CurrentUser(UserAccountRepository users) {
        this.users = users;
    }

    public UserAccount requireAccount(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ForbiddenException("AUTHENTICATION_REQUIRED", "请先登录");
        }
        return users.findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ForbiddenException("ACCOUNT_NOT_FOUND", "登录账号不存在"));
    }
}
