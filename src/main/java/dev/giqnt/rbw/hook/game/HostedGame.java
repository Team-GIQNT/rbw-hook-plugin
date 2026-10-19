package dev.giqnt.rbw.hook.game;

import com.google.gson.annotations.SerializedName;

/** A game.list entry. */
public record HostedGame(String id, State state, String mapName) {
    public enum State {
        @SerializedName("started") STARTED,
        /** The game is over but the api has not accepted its result yet. */
        @SerializedName("ended") ENDED
    }
}
