package dev.giqnt.rbw.hook.utils;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class APIUtils {
    private final HttpClient client = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String token;

    public APIUtils(final String projectName, final String token) {
        this.baseUrl = String.format("https://rbw.giqnt.dev/project/%s/", projectName);
        this.token = token;
    }

    private HttpRequest.Builder createRequestBuilder(final String endpoint) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + endpoint))
                .setHeader("Authorization", "Bearer " + token)
                .setHeader("Content-Type", "application/json");
    }

    public HttpResponse<String> request(
            @NonNull final String endpoint,
            @NonNull final String method,
            @Nullable final String body
    ) throws IOException, InterruptedException {
        final var builder = createRequestBuilder(endpoint);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
