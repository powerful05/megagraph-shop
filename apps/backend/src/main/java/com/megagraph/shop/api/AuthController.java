package com.megagraph.shop.api;

import com.megagraph.shop.identity.MerchantRepository;
import com.megagraph.shop.identity.MerchantStatus;
import com.megagraph.shop.identity.MerchantUserRepository;
import com.megagraph.shop.identity.TrialInviteService;
import com.megagraph.shop.identity.UserAccount;
import com.megagraph.shop.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.megagraph.shop.shared.DomainException;

@RestController
@RequestMapping("/api/auth/v1")
public class AuthController {

    public record CsrfResponse(String headerName, String token) {
    }

    public record RegisterRequest(
            @NotBlank String inviteCode,
            @NotBlank @Email String username,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Size(min = 12, max = 72) String password
    ) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record RegisteredUser(Long userId, String displayName) {
    }

    public record Membership(Long merchantId, String merchantName, String role) {
    }

    public record AuthMe(Long userId, String displayName, List<Membership> memberships) {
    }

    private final TrialInviteService invites;
    private final CurrentUser currentUser;
    private final MerchantUserRepository memberships;
    private final MerchantRepository merchants;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;

    public AuthController(
            TrialInviteService invites,
            CurrentUser currentUser,
            MerchantUserRepository memberships,
            MerchantRepository merchants,
            AuthenticationManager authenticationManager,
            SecurityContextRepository contextRepository
    ) {
        this.invites = invites;
        this.currentUser = currentUser;
        this.memberships = memberships;
        this.merchants = merchants;
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
    }

    @GetMapping("/csrf")
    CsrfResponse csrf(@RequestAttribute("_csrf") CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    RegisteredUser register(@Valid @RequestBody RegisterRequest request) {
        UserAccount account = invites.register(
                request.inviteCode(), request.username(), request.displayName(), request.password()
        );
        return new RegisteredUser(account.getId(), account.getDisplayName());
    }

    @PostMapping("/login")
    AuthMe login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            request.username().trim().toLowerCase(Locale.ROOT), request.password()
                    )
            );
        } catch (AuthenticationException exception) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误");
        }
        if (servletRequest.getSession(false) != null) {
            servletRequest.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, servletRequest, servletResponse);
        return me(authentication);
    }

    @GetMapping("/me")
    AuthMe me(Authentication authentication) {
        UserAccount account = currentUser.requireAccount(authentication);
        List<Membership> visible = memberships.findByUserId(account.getId()).stream()
                .flatMap(membership -> merchants.findById(membership.getMerchantId()).stream()
                        .filter(merchant -> merchant.getStatus() == MerchantStatus.ACTIVE)
                        .map(merchant -> new Membership(merchant.getId(), merchant.getName(),
                                membership.getRole().name())))
                .toList();
        return new AuthMe(account.getId(), account.getDisplayName(), visible);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }
}
