package org.leon.aimodule.chat.provider;

public final class ModelSelection {
    static final String PREFIX = "u:";

    private ModelSelection() {
    }

    public static String key(Long providerId, Long modelId) {
        return PREFIX + providerId + ":" + modelId;
    }

    public static boolean isPersonal(String key) {
        return key != null && key.trim().startsWith(PREFIX);
    }

    public static Ids parse(String key) {
        if (!isPersonal(key)) {
            return null;
        }
        String[] parts = key.trim().substring(PREFIX.length()).split(":");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new Ids(Long.valueOf(parts[0]), Long.valueOf(parts[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record Ids(Long providerId, Long modelId) {
    }
}
