package com.megagraph.shop.security;

import com.megagraph.shop.identity.UserAccount;
import com.megagraph.shop.identity.UserAccountRepository;
import com.megagraph.shop.identity.UserStatus;
import com.megagraph.shop.shared.DomainException;
import org.springframework.http.HttpStatus;
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
            throw new DomainException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "请先登录");
        }
        UserAccount account = users.findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new DomainException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "请重新登录"));
        if (account.getStatus() != UserStatus.ACTIVE) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "请重新登录");
        }
        return account;
    }
}
