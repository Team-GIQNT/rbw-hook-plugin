package dev.giqnt.rbw.hook.bedwars1058;

public class GameCreateException extends RuntimeException {
    public GameCreateException(String message) {
        super(message);
    }

    public GameCreateException(String message, Throwable cause) {
        super(message, cause);
    }
}
