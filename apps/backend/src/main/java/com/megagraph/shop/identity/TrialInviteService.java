package com.megagraph.shop.identity;

import com.megagraph.shop.shared.ConflictException;
import com.megagraph.shop.shared.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrialInviteService {

    private final TrialInviteRepository invites;
    private final UserAccountRepository users;
    private final PasswordEncoder passwords;
    private final SecureRandom random = new SecureRandom();

    public TrialInviteService(TrialInviteRepository invites, UserAccountRepository users, PasswordEncoder passwords) {
        this.invites = invites;
        this.users = users;
        this.passwords = passwords;
    }

    @Transactional
    public UserAccount register(String code, String username, String displayName, String password) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{40,80}")) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVITE_INVALID", "邀请码无效");
        }
        TrialInvite invite = invites.findByCodeHashForUpdate(hash(code))
                .orElseThrow(() -> new DomainException(HttpStatus.BAD_REQUEST, "INVITE_INVALID", "邀请码无效"));
        if (invite.getRedeemedAt() != null || !invite.getExpiresAt().isAfter(Instant.now())) {
            throw new ConflictException("INVITE_USED_OR_EXPIRED", "邀请码已使用或过期");
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        if (users.findByUsernameIgnoreCase(normalized).isPresent()) {
            throw new ConflictException("USERNAME_ALREADY_EXISTS", "账号已存在");
        }
        UserAccount account = users.saveAndFlush(new UserAccount(normalized, passwords.encode(password), displayName.trim()));
        invite.redeem(account.getId());
        return account;
    }

    @Transactional
    public String issue(Duration validFor) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        invites.save(new TrialInvite(hash(code), Instant.now().plus(validFor)));
        return code;
    }

    public static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
