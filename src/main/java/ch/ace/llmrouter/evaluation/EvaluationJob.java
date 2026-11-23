package ch.ace.llmrouter.evaluation;

import ch.ace.llmrouter.evaluation.EvaluationModel.Event;
import ch.ace.llmrouter.evaluation.EvaluationModel.EvaluationRow;
import ch.ace.llmrouter.evaluation.EvaluationModel.Export;
import ch.ace.llmrouter.evaluation.EvaluationModel.GoldRow;
import ch.ace.llmrouter.evaluation.EvaluationModel.JobState;
import ch.ace.llmrouter.evaluation.EvaluationModel.JobView;
import ch.ace.llmrouter.evaluation.EvaluationModel.Meta;
import ch.ace.llmrouter.evaluation.EvaluationModel.Summary;
import io.smallrye.mutiny.subscription.MultiEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** State of one backend evaluation run. All methods are synchronized; listeners get every event in order. */
public class EvaluationJob {

    private final String id;
    private final String fileName;
    private final Instant startedAt = Instant.now();
    /** Request texts, dropped when the job ends. */
    private List<GoldRow> input;
    private final int total;
    private final List<EvaluationRow> rows = new ArrayList<>();
    private final List<MultiEmitter<? super Event>> listeners = new ArrayList<>();
    private JobState state = JobState.RUNNING;
    private Instant finishedAt;
    private String error;
    private Summary summary;
    private volatile boolean cancelRequested;

    EvaluationJob(String id, String fileName, List<GoldRow> input) {
        this.id = id;
        this.fileName = fileName;
        this.input = input;
        this.total = input.size();
    }

    public String id() {
        return id;
    }

    synchronized List<GoldRow> input() {
        return input;
    }

    boolean cancelRequested() {
        return cancelRequested;
    }

    void requestCancel() {
        cancelRequested = true;
    }

    public synchronized JobState state() {
        return state;
    }

    synchronized void addRow(EvaluationRow row) {
        rows.add(row);
        publish(new Event("row", state, total, rows.size(), row, null, null));
    }

    synchronized void finish(JobState end, String error) {
        if (state != JobState.RUNNING) return;
        this.state = end;
        this.error = error;
        this.finishedAt = Instant.now();
        this.summary = EvaluationMath.summarize(rows);
        this.input = null;
        publish(new Event("done", state, total, rows.size(), null, summary, error));
        listeners.forEach(MultiEmitter::complete);
        listeners.clear();
    }

    /** Replays all rows so far, then streams live events; completes immediately for finished jobs. */
    synchronized void subscribe(MultiEmitter<? super Event> emitter) {
        emitter.emit(new Event("status", state, total, rows.size(), null, null, null));
        int done = 0;
        for (EvaluationRow r : rows) {
            emitter.emit(new Event("row", state, total, ++done, r, null, null));
        }
        if (state != JobState.RUNNING) {
            emitter.emit(new Event("done", state, total, rows.size(), null, summary, error));
            emitter.complete();
            return;
        }
        listeners.add(emitter);
        emitter.onTermination(() -> unsubscribe(emitter));
    }

    private synchronized void unsubscribe(MultiEmitter<? super Event> emitter) {
        listeners.remove(emitter);
    }

    private void publish(Event e) {
        for (MultiEmitter<? super Event> l : List.copyOf(listeners)) {
            try {
                l.emit(e);
            } catch (RuntimeException ignored) {
                listeners.remove(l);
            }
        }
    }

    public synchronized JobView view() {
        return new JobView(id, fileName, "backend", state, total, rows.size(), startedAt.toString(),
                finishedAt == null ? null : finishedAt.toString(), error, List.copyOf(rows), summary);
    }

    public synchronized Export export() {
        return new Export(new Meta(id, fileName, "backend", state, startedAt.toString(),
                finishedAt == null ? null : finishedAt.toString(), total),
                summary == null ? EvaluationMath.summarize(rows) : summary, List.copyOf(rows));
    }
}
