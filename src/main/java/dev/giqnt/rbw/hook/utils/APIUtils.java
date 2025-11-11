package dev.giqnt.rbw.hook.utils;

import lombok.RequiredArgsConstructor;
import okhttp3.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;

@RequiredArgsConstructor
public class APIUtils {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final String projectName;
    private final String token;
    private final OkHttpClient client = new OkHttpClient();

    @NonNull
    public String request(
            final int version,
            @NonNull final String endpoint,
            @NonNull final String method,
            @Nullable final String body
    ) throws IOException {
        return request(version, endpoint, method, body, response -> {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response.code() + ": " + response.message());
            }
            final var responseBody = response.body();
            return responseBody == null ? "" : responseBody.string();
        });
    }

    public <T> T request(
            final int version,
            @NonNull final String endpoint,
            @NonNull final String method,
            @Nullable final String body,
            @NonNull final ResponseHandler<T> handler
    ) throws IOException {
        final StringBuilder urlBuilder = new StringBuilder("https://rbw.giqnt.dev/api/v");
        urlBuilder.append(version);
        urlBuilder.append("/projects/");
        urlBuilder.append(projectName);
        if (!endpoint.startsWith("/")) {
            urlBuilder.append("/");
        }
        urlBuilder.append(endpoint);
        final Request request = new Request.Builder()
                .url(urlBuilder.toString())
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Content-Type", "application/json")
                .method(method, body == null ? null : RequestBody.create(body, JSON))
                .build();
        try (final Response response = client.newCall(request).execute()) {
            return handler.handle(response);
        }
    }

    @NonNull
    public String requestLegacy(
            @NonNull final String endpoint,
            @NonNull final String method,
            @Nullable final String body
    ) throws IOException {
        return requestLegacy(endpoint, method, body, response -> {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response.code() + ": " + response.message());
            }
            final var responseBody = response.body();
            return responseBody == null ? "" : responseBody.string();
        });
    }

    public <T> T requestLegacy(
            @NonNull final String endpoint,
            @NonNull final String method,
            @Nullable final String body,
            @NonNull final ResponseHandler<T> handler
    ) throws IOException {
        final StringBuilder urlBuilder = new StringBuilder("https://rbw.giqnt.dev/project/");
        urlBuilder.append(projectName);
        if (!endpoint.startsWith("/")) {
            urlBuilder.append("/");
        }
        urlBuilder.append(endpoint);
        final Request request = new Request.Builder()
                .url(urlBuilder.toString())
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
