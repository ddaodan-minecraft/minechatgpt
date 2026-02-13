package com.ddaodan.MineChatGPT.service;

import com.ddaodan.MineChatGPT.ConfigManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimiter {
    public static final class Decision {
        public final boolean allowed;
        public final String reason;
        public final long retryAfterMs;

        private Decision(boolean allowed, String reason, long retryAfterMs) {
            this.allowed = allowed;
            this.reason = reason;
            this.retryAfterMs = retryAfterMs;
        }

        public static Decision allow() {
            return new Decision(true, null, 0);
        }

        public static Decision deny(String reason, long retryAfterMs) {
            return new Decision(false, reason, retryAfterMs);
        }
    }

    private static final class UserBuckets {
        private final TokenBucket requests;
        private final TokenBucket tokens;

        private UserBuckets(TokenBucket requests, TokenBucket tokens) {
            this.requests = requests;
            this.tokens = tokens;
        }
    }

    private final ConfigManager configManager;
    private final Map<String, UserBuckets> perUser = new ConcurrentHashMap<>();
    private final Map<String, Long> nextAllowedAt = new ConcurrentHashMap<>();

    private volatile TokenBucket globalRequests;
    private volatile TokenBucket globalTokens;

    public RateLimiter(ConfigManager configManager) {
        this.configManager = configManager;
        long now = System.currentTimeMillis();
        this.globalRequests = new TokenBucket(
                configManager.getGlobalBurstRequests(),
                configManager.getGlobalRequestsPerMinute() / 60.0,
                now
        );
        this.globalTokens = new TokenBucket(
                configManager.getGlobalBurstTokens(),
                configManager.getGlobalTokensPerMinute() / 60.0,
                now
        );
    }

    public Decision tryAcquire(String userId, int estimatedTotalTokens, long nowMillis) {
        if (!configManager.isRateLimitEnabled()) {
            return Decision.allow();
        }

        long cooldownMs = configManager.getRateLimitCooldownMs();
        Long next = nextAllowedAt.get(userId);
        if (next != null && nowMillis < next) {
            return Decision.deny("cooldown", next - nowMillis);
        }

        String mode = configManager.getRateLimitMode();
        boolean limitRequests = "requests".equalsIgnoreCase(mode) || "both".equalsIgnoreCase(mode);
        boolean limitTokens = "tokens".equalsIgnoreCase(mode) || "both".equalsIgnoreCase(mode);

        UserBuckets buckets = perUser.computeIfAbsent(userId, id -> {
            TokenBucket req = new TokenBucket(
                    configManager.getPerUserBurstRequests(),
                    configManager.getPerUserRequestsPerMinute() / 60.0,
                    nowMillis
            );
            TokenBucket tok = new TokenBucket(
                    configManager.getPerUserBurstTokens(),
                    configManager.getPerUserTokensPerMinute() / 60.0,
                    nowMillis
            );
            return new UserBuckets(req, tok);
        });

        if (limitRequests) {
            if (!globalRequests.tryConsume(1.0, nowMillis) || !buckets.requests.tryConsume(1.0, nowMillis)) {
                return Decision.deny("rate_limited", 0);
            }
        }

        if (limitTokens) {
            double cost = Math.max(0, estimatedTotalTokens);
            if (!globalTokens.tryConsume(cost, nowMillis) || !buckets.tokens.tryConsume(cost, nowMillis)) {
                return Decision.deny("rate_limited", 0);
            }
        }

        if (cooldownMs > 0) {
            nextAllowedAt.put(userId, nowMillis + cooldownMs);
        }

        return Decision.allow();
    }
}

