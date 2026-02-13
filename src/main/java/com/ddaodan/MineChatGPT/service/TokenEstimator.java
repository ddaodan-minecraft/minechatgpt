package com.ddaodan.MineChatGPT.service;

public interface TokenEstimator {
    int estimateTextTokens(String text);

    default int estimateMessageTokens(String role, String content) {
        int base = 4; // message overhead (approx)
        return base + estimateTextTokens(role) + estimateTextTokens(content);
    }
}

