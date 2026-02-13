package com.ddaodan.MineChatGPT.service;

public class ApproxCharTokenEstimator implements TokenEstimator {
    @Override
    public int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int chars = text.length();
        return (int) Math.ceil(chars / 4.0);
    }
}

