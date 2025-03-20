package dev.giqnt.rbw.hook.bedwars1058;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public record APIUtils(HookPlugin plugin) {
    public HttpResponse<String> request(final HttpRequest request) throws IOException, InterruptedException {
        return HttpClient
                .newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }
}
