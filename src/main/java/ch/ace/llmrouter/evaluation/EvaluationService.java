package ch.ace.llmrouter.evaluation;

import ch.ace.llmrouter.api.Dtos.RouteResponse;
import ch.ace.llmrouter.config.RouterConfig;
import ch.ace.llmrouter.evaluation.EvaluationModel.Export;
import ch.ace.llmrouter.evaluation.EvaluationModel.GoldRow;
import ch.ace.llmrouter.evaluation.EvaluationModel.JobState;
import ch.ace.llmrouter.evaluation.EvaluationModel.JobView;
import ch.ace.llmrouter.routing.RouterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * Runs gold-set evaluations in the backend: one job at a time, each row on its own via
 * {@link RouterService#route} with dryRun. Finished results (without request text) are written to
 * {@code router.evaluation.dir/<id>.json}.
 */
@Singleton
public class EvaluationService {

    private static final Logger LOG = Logger.getLogger(EvaluationService.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Inject
    RouterConfig config;

    @Inject
    RouterService router;

    @Inject
    ObjectMapper mapper;

    private final Map<String, EvaluationJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "evaluation-runner");
        t.setDaemon(true);
        return t;
    });
    private ExecutorService rowPool;
    private volatile EvaluationJob current;

    @PostConstruct
    void init() {
        rowPool = Executors.newFixedThreadPool(Math.max(1, config.evaluation().parallelism()));
    }

    void shutdown(@Observes ShutdownEvent ev) {
        runner.shutdownNow();
        rowPool.shutdownNow();
    }

    public static class JobRunningException extends RuntimeException {
        public JobRunningException(String id) {
            super("Es läuft bereits eine Evaluation (" + id + ").");
        }
    }

    public synchronized JobView start(String fileName, List<GoldRow> rows) {
        EvaluationJob running = current;
        if (running != null && running.state() == JobState.RUNNING) {
            throw new JobRunningException(running.id());
        }
        EvaluationJob job = new EvaluationJob(UUID.randomUUID().toString(), fileName, rows);
        jobs.put(job.id(), job);
        current = job;
        runner.submit(() -> run(job));
        LOG.infof("Evaluation %s started: %s, %d rows", job.id(), fileName, rows.size());
        return job.view();
    }

    private void run(EvaluationJob job) {
        try {
            List<GoldRow> input = job.input();
            int parallel = Math.max(1, config.evaluation().parallelism());
            for (int i = 0; i < input.size(); i += parallel) {
                if (job.cancelRequested()) {
                    job.finish(JobState.CANCELLED, null);
                    persist(job);
                    return;
                }
                List<Future<EvaluationModel.EvaluationRow>> batch = input.subList(i, Math.min(i + parallel, input.size()))
                        .stream().map(g -> rowPool.submit(() -> evaluate(g))).toList();
                for (Future<EvaluationModel.EvaluationRow> f : batch) {
                    job.addRow(f.get());
                }
            }
            job.finish(JobState.DONE, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.finish(JobState.FAILED, "interrupted");
        } catch (Exception e) {
            LOG.errorf(e, "Evaluation %s failed", job.id());
            job.finish(JobState.FAILED, e.getMessage());
        }
        persist(job);
        LOG.infof("Evaluation %s finished: %s", job.id(), job.state());
    }

    /** One row: always on its own, always dryRun. Errors become error rows, the job goes on. */
    EvaluationModel.EvaluationRow evaluate(GoldRow g) {
        long t0 = System.nanoTime();
        try {
            RouteResponse r = router.route(g.anfrage(), List.of(), false, true);
            return EvaluationMath.toRow(g.nr(), g.gold(), r, (System.nanoTime() - t0) / 1_000_000, null);
        } catch (RuntimeException e) {
            return EvaluationMath.toRow(g.nr(), g.gold(), null, (System.nanoTime() - t0) / 1_000_000,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public Optional<EvaluationJob> job(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public Optional<JobView> view(String id) {
        EvaluationJob job = jobs.get(id);
        if (job != null) return Optional.of(job.view());
        return load(id).map(e -> new JobView(e.meta().id(), e.meta().fileName(), e.meta().mode(), e.meta().state(),
                e.meta().total(), e.rows().size(), e.meta().startedAt(), e.meta().finishedAt(), null, e.rows(),
                e.summary()));
    }

    public Optional<Export> export(String id) {
        EvaluationJob job = jobs.get(id);
        return job != null ? Optional.of(job.export()) : load(id);
    }

    public Optional<JobView> cancel(String id) {
        EvaluationJob job = jobs.get(id);
        if (job == null) return Optional.empty();
        job.requestCancel();
        return Optional.of(job.view());
    }

    private void persist(EvaluationJob job) {
        try {
            Path dir = Path.of(config.evaluation().dir());
            Files.createDirectories(dir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(job.id() + ".json").toFile(), job.export());
        } catch (IOException e) {
            LOG.warnf("Cannot write evaluation result %s: %s", job.id(), e.getMessage());
        }
    }

    /** Finished results survive restarts as files. */
    private Optional<Export> load(String id) {
        if (!SAFE_ID.matcher(id).matches()) return Optional.empty();
        Path file = Path.of(config.evaluation().dir()).resolve(id + ".json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(file.toFile(), Export.class));
        } catch (IOException e) {
            LOG.warnf("Cannot read evaluation result %s: %s", id, e.getMessage());
            return Optional.empty();
        }
    }
}
