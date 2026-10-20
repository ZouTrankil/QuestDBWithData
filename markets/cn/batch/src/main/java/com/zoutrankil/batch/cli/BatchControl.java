package com.zoutrankil.batch.cli;

import com.zoutrankil.batch.api.ManagementServer;
import com.zoutrankil.batch.domain.RunRequest;
import com.zoutrankil.batch.source.SourceCollector;
import com.zoutrankil.batch.support.Json;
import com.zoutrankil.runtime.ProjectConfiguration;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Bounded CLI client for the same authenticated API; no shell/module execution. */
public final class BatchControl {
    private BatchControl() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1)
            throw new IllegalArgumentException(
                    "tasks | runs | reconciliation | run <request.json> | detail <instance> | sources | collect <request.json> <request-id> | l2-inspect <archive-path> | schedules | pause | resume");
        var environment = ProjectConfiguration.load("batch");
        String token = environment.getRequiredProperty("jdb.api-token");
        if (token.length() < 24)
            throw new IllegalArgumentException("jdb.api-token / JDB_API_TOKEN required");
        String base = environment.getRequiredProperty("jdb.api-url");
        var uri = URI.create(base);
        if (!InetAddress.getByName(uri.getHost()).isLoopbackAddress())
            throw new IllegalArgumentException("Management client is restricted to loopback");
        String path;
        String method = "GET";
        String body = "";
        String id = null;
        switch (args[0]) {
            case "tasks", "runs", "reconciliation", "schedules", "sources" -> {
                if (args.length != 1) throw new IllegalArgumentException("Unexpected arguments");
                path = "/v1/" + args[0];
            }
            case "detail" -> {
                if (args.length != 2 || !args[1].matches("[a-f0-9]{64}"))
                    throw new IllegalArgumentException("Instance ID required");
                path = "/v1/runs/" + args[1];
            }
            case "collect" -> {
                if (args.length != 3 || Files.size(Path.of(args[1])) > 1024 * 1024)
                    throw new IllegalArgumentException(
                            "Bounded source JSON and request ID required");
                body = Files.readString(Path.of(args[1]));
                Json.read(body, SourceCollector.Request.class);
                id = args[2];
                path = "/v1/sources/collect";
                method = "POST";
            }
            case "l2-inspect" -> {
                if (args.length != 2 || args[1].length() > 2048)
                    throw new IllegalArgumentException("Archive path required");
                body = Json.write(new ManagementServer.ArchiveInspectionRequest(args[1]));
                path = "/v1/l2/archives/inspect";
                method = "POST";
            }
            case "run" -> {
                if (args.length != 2 || Files.size(Path.of(args[1])) > 65536)
                    throw new IllegalArgumentException("Bounded request JSON required");
                body = Files.readString(Path.of(args[1]));
                id = Json.read(body, RunRequest.class).requestId();
                path = "/v1/runs";
                method = "POST";
            }
            case "pause", "resume" -> {
                if (args.length != 1) throw new IllegalArgumentException("Unexpected arguments");
                path = "/v1/schedules/post_close/" + args[0];
                method = "POST";
            }
            default -> throw new IllegalArgumentException("Unknown operation");
        }
        var request =
                HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(Duration.ofMinutes(2))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (id != null) request.header("Idempotency-Key", id);
        var response =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build()
                        .send(request.build(), HttpResponse.BodyHandlers.ofString());
        System.out.println(response.body());
        if (response.statusCode() >= 400)
            throw new IllegalStateException(
                    "Management request failed: HTTP " + response.statusCode());
    }
}
