package com.ddaodan.MineChatGPT;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class ConversationContext {
    public static final class Message {
        private final String role;
        private final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }
    }

    private final Deque<Message> history;
    private final int maxHistorySize;

    public ConversationContext(int maxHistorySize) {
        this.maxHistorySize = maxHistorySize;
        this.history = new ArrayDeque<>();
    }

    public void addUserMessage(String message) {
        addMessage("user", message);
    }

    public void addAssistantMessage(String message) {
        addMessage("assistant", message);
    }

    private void addMessage(String role, String message) {
        if (history.size() >= maxHistorySize) {
            history.pollFirst();
        }
        history.addLast(new Message(role, message));
    }

    public List<Message> getMessages() {
        return new ArrayList<>(history);
    }

    public void setMessages(List<Message> messages) {
        history.clear();
        if (messages == null || messages.isEmpty()) {
            return;
        }
        int startIndex = Math.max(0, messages.size() - maxHistorySize);
        for (int i = startIndex; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg != null) {
                history.addLast(new Message(msg.getRole(), msg.getContent()));
            }
        }
    }

    public void trimToLast(int keepLast) {
        if (keepLast < 0) {
            keepLast = 0;
        }
        while (history.size() > keepLast) {
            history.pollFirst();
        }
    }

    public void removeOldestMessage() {
        history.pollFirst();
    }

    public void clearHistory() {
        history.clear();
    }
}
