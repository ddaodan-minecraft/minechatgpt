package com.ddaodan.MineChatGPT.service;

public class TokenBucket {
    private final double capacity;
    private final double refillTokensPerMillis;
    private double tokens;
    private long lastRefillMillis;

    public TokenBucket(double capacity, double refillTokensPerSecond, long nowMillis) {
        this.capacity = Math.max(0.0, capacity);
        this.refillTokensPerMillis = Math.max(0.0, refillTokensPerSecond) / 1000.0;
        this.tokens = this.capacity;
        this.lastRefillMillis = nowMillis;
    }

    public synchronized boolean tryConsume(double amount, long nowMillis) {
        refill(nowMillis);
        if (amount <= 0) {
            return true;
        }
        if (tokens >= amount) {
            tokens -= amount;
            return true;
        }
        return false;
    }

    public synchronized void refund(double amount, long nowMillis) {
        refill(nowMillis);
        if (amount <= 0) {
            return;
        }
        tokens = Math.min(capacity, tokens + amount);
    }

    private void refill(long nowMillis) {
        if (nowMillis <= lastRefillMillis) {
            return;
        }
        double elapsed = nowMillis - lastRefillMillis;
        double add = elapsed * refillTokensPerMillis;
        if (add > 0) {
            tokens = Math.min(capacity, tokens + add);
        }
        lastRefillMillis = nowMillis;
    }
}

