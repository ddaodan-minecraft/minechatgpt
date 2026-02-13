package com.ddaodan.MineChatGPT.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public class RequestQueue<T> {
    public static final class EnqueueResult {
        public final boolean accepted;
        public final int position;
        public final String reason;

        private EnqueueResult(boolean accepted, int position, String reason) {
            this.accepted = accepted;
            this.position = position;
            this.reason = reason;
        }

        public static EnqueueResult accepted(int position) {
            return new EnqueueResult(true, position, null);
        }

        public static EnqueueResult rejected(String reason) {
            return new EnqueueResult(false, -1, reason);
        }
    }

    private final int maxSize;
    private final int maxPerUser;
    private final Deque<Entry<T>> queue = new ArrayDeque<>();
    private final Map<String, Integer> perUserCounts = new HashMap<>();

    private static final class Entry<T> {
        private final String userId;
        private final T item;

        private Entry(String userId, T item) {
            this.userId = userId;
            this.item = item;
        }
    }

    public RequestQueue(int maxSize, int maxPerUser) {
        this.maxSize = Math.max(0, maxSize);
        this.maxPerUser = Math.max(0, maxPerUser);
    }

    public synchronized EnqueueResult tryEnqueue(String userId, T item) {
        if (maxSize > 0 && queue.size() >= maxSize) {
            return EnqueueResult.rejected("queue_full");
        }
        int count = perUserCounts.getOrDefault(userId, 0);
        if (maxPerUser > 0 && count >= maxPerUser) {
            return EnqueueResult.rejected("queue_full_user");
        }
        queue.addLast(new Entry<>(userId, item));
        perUserCounts.put(userId, count + 1);
        return EnqueueResult.accepted(queue.size());
    }

    public synchronized T poll() {
        Entry<T> entry = queue.pollFirst();
        if (entry == null) {
            return null;
        }
        Integer count = perUserCounts.get(entry.userId);
        if (count != null) {
            int next = count - 1;
            if (next <= 0) {
                perUserCounts.remove(entry.userId);
            } else {
                perUserCounts.put(entry.userId, next);
            }
        }
        return entry.item;
    }

    public synchronized int size() {
        return queue.size();
    }
}

