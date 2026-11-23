package ch.ace.llmrouter;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/** Starts one WireMock server that stands in for both kev and Ollama. */
public class WireMockResource implements QuarkusTestResourceLifecycleManager {

    public static WireMockServer server;

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        String url = "http://localhost:" + server.port();
        try {
            Path log = Files.createTempFile("router-log", ".jsonl");
            Files.delete(log);
            return Map.of(
                    "router.kev.url", url,
                    "router.ollama.url", url,
                    "router.log.file", log.toString(),
                    "router.evaluation.dir", Files.createTempDirectory("evaluations").toString(),
                    "router.api-key.enabled", "true",
                    "router.api-key.value", "test-key",
                    "router.kev.timeout", "5s",
                    "router.ollama.timeout", "5s");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}
