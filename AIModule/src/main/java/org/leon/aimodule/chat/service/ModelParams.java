package org.leon.aimodule.chat.service;

public record ModelParams(String modelKey, Double temperature, Integer maxTokens, boolean supportsThinking) {
}
