package com.megagraph.shop.security;

import java.util.ArrayList;
import java.util.List;
import com.megagraph.shop.identity.MerchantUserRepository;
import com.megagraph.shop.identity.UserAccount;
import com.megagraph.shop.identity.UserAccountRepository;
import com.megagraph.shop.identity.UserStatus;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserAccountRepository users;
    private final MerchantUserRepository memberships;

    public DatabaseUserDetailsService(UserAccountRepository users, MerchantUserRepository memberships) {
        this.users = users;
        this.memberships = memberships;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = users.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));

        List<String> authorities = new ArrayList<>(memberships.findByUserId(account.getId()).stream()
                .map(membership -> "ROLE_MERCHANT_" + membership.getRole().name())
                .toList());
        authorities.add("ROLE_CUSTOMER");

        return User.withUsername(account.getUsername())
                .password(account.getPasswordHash())
                .authorities(authorities.toArray(String[]::new))
                .disabled(account.getStatus() != UserStatus.ACTIVE)
                .build();
    }
}
