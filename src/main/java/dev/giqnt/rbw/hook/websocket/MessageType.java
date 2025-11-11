package dev.giqnt.rbw.hook.websocket;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

@RequiredArgsConstructor
public enum MessageType {
    EVENT("event"), REQUEST("request"), RESPONSE("response");

    @Nullable
    public static MessageType byValue(final String value) {
        return switch (value) {
            case "event" -> MessageType.EVENT;
            case "request" -> MessageType.REQUEST;
            case "response" -> MessageType.RESPONSE;
            default -> null;
        };
    }

    @Getter
    private final String value;

    @Override
    public String toString() {
        return value;
    }
}
