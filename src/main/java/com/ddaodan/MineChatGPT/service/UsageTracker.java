package com.ddaodan.MineChatGPT.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class UsageTracker {
    public static final class Snapshot {
        public final long requests;
        public final long promptTokens;
        public final long completionTokens;
        public final long totalTokens;

        private Snapshot(long requests, long promptTokens, long completionTokens, long totalTokens) {
            this.requests = requests;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }
    }

    private static final class Counters {
        private final LongAdder requests = new LongAdder();
        private final LongAdder promptTokens = new LongAdder();
        private final LongAdder completionTokens = new LongAdder();
        private final LongAdder totalTokens = new LongAdder();

        private Snapshot snapshot() {
            return new Snapshot(
                    requests.sum(),
                    promptTokens.sum(),
                    completionTokens.sum(),
                    totalTokens.sum()
            );
        }
    }

    private final Counters global = new Counters();
    private final Map<String, Counters> perUser = new ConcurrentHashMap<>();

    public void record(String userId, long promptTokens, long completionTokens, long totalTokens) {
        global.requests.increment();
        global.promptTokens.add(promptTokens);
        global.completionTokens.add(completionTokens);
        global.totalTokens.add(totalTokens);

        Counters user = perUser.computeIfAbsent(userId, k -> new Counters());
        user.requests.increment();
        user.promptTokens.add(promptTokens);
        user.completionTokens.add(completionTokens);
        user.totalTokens.add(totalTokens);
    }

    public Snapshot getGlobal() {
        return global.snapshot();
    }

    public Snapshot getUser(String userId) {
        Counters user = perUser.get(userId);
        if (user == null) {
            return new Snapshot(0, 0, 0, 0);
        }
        return user.snapshot();
    }

    public void reset() {
        perUser.clear();
        // global LongAdder has no reset without recreating
        // Create a new UsageTracker if you need a hard reset
    }
}

