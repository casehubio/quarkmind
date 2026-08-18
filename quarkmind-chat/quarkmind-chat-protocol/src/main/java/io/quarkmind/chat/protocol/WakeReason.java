package io.quarkmind.chat.protocol;

public enum WakeReason {
    MESSAGE,
    HEARTBEAT;

    public static WakeReason fromDriverSource(String source) {
        if ("timer".equals(source)) return HEARTBEAT;
        return MESSAGE;
    }
}
