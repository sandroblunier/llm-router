package ch.ace.llmrouter.log;

import ch.ace.llmrouter.config.RouterConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only JSONL log of routing events with an in-memory index by request id.
 * The index is rebuilt from the file at startup.
 */
@Startup
@Singleton
public class DecisionLog {

    private static final Logger LOG = Logger.getLogger(DecisionLog.class);

    @Inject
    RouterConfig config;

    @Inject
    ObjectMapper mapper;

    private final List<LogEvent> all = new ArrayList<>();
    /** Insertion order = order of first event per id. */
    private final LinkedHashMap<String, List<LogEvent>> byId = new LinkedHashMap<>();
    private Path file;

    @PostConstruct
    void init() {
        file = Path.of(config.log().file());
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            if (Files.exists(file)) {
                int lineNo = 0;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    lineNo++;
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        index(mapper.readValue(line, LogEvent.class));
                    } catch (IOException e) {
                        LOG.warnf("Skipping unreadable log line %d: %s", lineNo, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open log file " + file, e);
        }
        LOG.infof("Decision log %s loaded: %d events, %d requests", file, all.size(), byId.size());
    }

    public synchronized void append(LogEvent event) {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(mapper.writeValueAsString(event));
            w.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write log file " + file, e);
        }
        index(event);
    }

    public synchronized List<LogEvent> all() {
        return List.copyOf(all);
    }

    public synchronized List<LogEvent> byId(String id) {
        List<LogEvent> events = byId.get(id);
        return events == null ? List.of() : List.copyOf(events);
    }

    /** The last {@code count} requests (by first event), newest first, each with all its events. */
    public synchronized Map<String, List<LogEvent>> latest(int count) {
        List<String> ids = new ArrayList<>(byId.keySet());
        Map<String, List<LogEvent>> result = new LinkedHashMap<>();
        for (int i = ids.size() - 1; i >= 0 && result.size() < count; i--) {
            String id = ids.get(i);
            result.put(id, List.copyOf(byId.get(id)));
        }
        return result;
    }

    private void index(LogEvent event) {
        all.add(event);
        byId.computeIfAbsent(event.id(), k -> new ArrayList<>()).add(event);
    }
}
