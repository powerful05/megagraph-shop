package com.megagraph.shop.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.megagraph.shop.shared.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(name = "app.security.login-rate-limit-enabled", havingValue = "true", matchIfMissing = true)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private record Window(long expiresAt, int count) {
    }

    private static final long WINDOW_SECONDS = 15 * 60;
    private static final int MAX_ATTEMPTS = 20;
    private static final int MAX_KEYS = 10_000;

    private final ConcurrentHashMap<String, Window> attempts = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public AuthRateLimitFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return true;
        return !request.getRequestURI().equals("/api/auth/v1/login")
                && !request.getRequestURI().equals("/api/auth/v1/register");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = Instant.now().getEpochSecond();
        if (attempts.size() >= MAX_KEYS) {
            attempts.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        }
        String key = request.getRemoteAddr() + ':' + request.getRequestURI();
        if (attempts.size() >= MAX_KEYS && !attempts.containsKey(key)) {
            reject(response, WINDOW_SECONDS);
            return;
        }
        Window window = attempts.compute(key, (ignored, existing) -> {
            if (existing == null || existing.expiresAt <= now) return new Window(now + WINDOW_SECONDS, 1);
            return new Window(existing.expiresAt, existing.count + 1);
        });
        if (window.count > MAX_ATTEMPTS) {
            reject(response, Math.max(1, window.expiresAt - now));
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, long retryAfter) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getWriter(), ApiError.of("AUTH_RATE_LIMITED", "请求过于频繁，请稍后重试"));
    }
}
