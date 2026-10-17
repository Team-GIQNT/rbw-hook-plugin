package dev.giqnt.rbw.hook.game;

import com.google.gson.annotations.SerializedName;
import org.jspecify.annotations.Nullable;

/** A game.list entry. mapName is null only while creating. */
public record HostedGame(String id, State state, @Nullable String mapName) {
    public enum State {
        /** game.create is still in progress. */
        @SerializedName("creating") CREATING,
        @SerializedName("started") STARTED,
        /** The game is over but the api has not accepted its result yet. */
        @SerializedName("ended") ENDED
    }
}
