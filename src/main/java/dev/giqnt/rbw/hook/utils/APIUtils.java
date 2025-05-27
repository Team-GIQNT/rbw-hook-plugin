package dev.giqnt.rbw.hook.utils;

import okhttp3.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;

public class APIUtils {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client;
    private final String baseUrl;
    private final String token;

    public APIUtils(@NonNull final String projectName,
                    @NonNull final String token) {
        this.baseUrl = String.format("https://rbw.giqnt.dev/project/%s/", projectName);
        this.token = token;
        this.client = new OkHttpClient();
    }

    @NonNull
    public String request(
            @NonNull final String endpoint,
            @NonNull final String method,
            @Nullable final String body
    ) throws IOException {
        return request(endpoint, method, body, response -> {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response.code() + ": " + response.message());
            }
            final var responseBody = response.body();
            return responseBody == null ? "" : responseBody.string();
        });
    }

    public <T> T request(
            @NonNull final String endpoint,
            @NonNull final String method,
            @Nullable final String body,
            @NonNull final ResponseHandler<T> handler
    ) throws IOException {
        final Request request = new Request.Builder()
                .url(baseUrl + endpoint)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Content-Type", "application/json")
                .method(method, body == null ? null : RequestBody.create(body, JSON))
                .build();
        try (final Response response = client.newCall(request).execute()) {
            return handler.handle(response);
        }
    }

    @FunctionalInterface
    public interface ResponseHandler<T> {
        T handle(Response response) throws IOException;
    }
}
