# LLM Router – Backend

Der Router entscheidet für jede Anfrage: lokales LLM oder Cloud-LLM.
Das Entscheidungsmodell ist [kev](https://github.com/jaredpalmer/kev).
Die Antworten kommen von Ollama (lokal `gemma3:4b`, Cloud `gemma4:31b-cloud`).

## Entscheidungsmodell: zwei Varianten

`router.decision.backend` wählt die Variante:

| Wert | Aufruf | Wahrscheinlichkeit D0–D3 |
|---|---|---|
| `ollama` (Standard) | kev-Modell in Ollama, `POST /api/chat`, Temperatur 0, Antwort als JSON-Schema | aus den Token-Wahrscheinlichkeiten (`logprobs`) an der Stelle der Datenschutzstufe |
| `kev-server` | kev-Python-Server, `POST /v1/systemone` | direkt von kev |

Wichtig für `ollama`:
- `router.decision.ollama.model` muss der Name aus `ollama list` sein (Platzhalter: `kev`).
- `router.decision.ollama.num-ctx` muss zum Modell passen (`ollama show <modell>`).
- Ollama muss `logprobs` liefern (neuere Ollama-Version). Ohne `logprobs` geht jede Anfrage lokal (Grund `kev_error`).
- Der System-Prompt steht in `src/main/resources/decision-prompt.txt` (Richtlinie annotation_pilot).

## Ablauf

1. Client sendet die Anfrage an `POST /route` (optional mit Anhängen).
2. Der Router extrahiert den Text der Anhänge (PDF, DOCX, TXT).
3. Das Entscheidungsmodell beurteilt den Text in **einem** Aufruf:
   - `datenschutzstufe`: D0, D1, D2 oder D3 (mit Wahrscheinlichkeiten)
   - `komplexitaet`: einfach, mittel oder anspruchsvoll
   - PII-Arten (18 erlaubte Begriffe)
4. Der Router wählt das Ziel (siehe Regeln unten), vergibt eine UUID und loggt den Entscheid.
5. Der Client erhält den Entscheid und sendet `OK` oder `NOK`.
   - `OK`: Der Router leitet die Anfrage weiter und gibt die Antwort zurück.
   - `NOK`: Der Router loggt das Feedback und bricht ab.

## Routing-Regeln

- **Cloud** nur, wenn beide Bedingungen gelten:
  - Die Summe der kev-Wahrscheinlichkeiten der erlaubten Stufen ist mindestens die Schwelle
    (Standard: nur D0, Schwelle 0.95).
  - Die Komplexität ist in `cloud-complexities` (Standard: nur `anspruchsvoll`).
- Sonst **lokal** nach Komplexität: `LOCAL_SIMPLE`, `LOCAL_MEDIUM`, `LOCAL_COMPLEX`.
- Text zu lang (Schätzung über dem Limit, kev antwortet 422 oder Ollama-Kontext voll) → `LOCAL_COMPLEX`, Grund `too_long`.
- Entscheidungsmodell nicht erreichbar, ungültige Antwort oder keine `logprobs` → `LOCAL_COMPLEX`, Grund `kev_error`.
- Ziel-LLM antwortet nicht → HTTP 502 mit ID. Es gibt **keinen** Wechsel in die Cloud.

Gründe im Feld `reasons.reason`: `cloud_eligible`, `privacy_below_threshold`, `complexity_local`, `too_long`, `kev_error`.

## Voraussetzungen

- Java 26 (z. B. Temurin 26: `brew install --cask temurin@26`) und Maven 3.9 (`brew install maven`); `JAVA_HOME` muss auf JDK 26 zeigen
- Quarkus 3.40 LTS (Core-BOM `io.quarkus:quarkus-bom`)
- Ollama mit den Modellen (kev-Modell: Name siehe `ollama list`):
  ```bash
  ollama pull gemma3:4b
  ollama signin            # für Cloud-Modelle
  ollama pull gemma4:31b-cloud
  ```
- Nur für `router.decision.backend=kev-server`: der kev-Server
  ```bash
  cd kev && uv sync --extra serve
  uv run --extra serve python -m kev.serve --run jaredpalmer/kev-4b --port 8009
  ```

## Starten

### Entwicklung

```bash
mvn quarkus:dev -Drouter.decision.ollama.model=<kev-tag>   # Ollama auf localhost:11434, API-Key "dev-key"
```

### Tests

```bash
mvn test                  # Unit-Tests + Integrationstests (WireMock ersetzt kev und Ollama)
```

### Docker

```bash
ROUTER_API_KEY=geheim docker compose up --build
ROUTER_API_KEY=geheim ./smoke-test.sh          # gegen echte kev- und Ollama-Dienste
```

Multi-Arch-Image (DGX Spark und amd64):

```bash
docker buildx build --platform linux/arm64,linux/amd64 -t llm-router:0.1.0 .
```

## REST-Schnittstelle

Alle Aufrufe brauchen den Header `X-API-Key`. Ausnahme: `/q/health`.

| Methode | Pfad | Zweck |
|---|---|---|
| POST | `/route` | Anfrage beurteilen. JSON `{"prompt", "autoConfirm", "dryRun"}` oder multipart mit `prompt`, `files`, `autoConfirm`, `dryRun` |
| POST | `/requests/{id}/feedback` | `{"feedback":"OK"}` oder `{"feedback":"NOK"}` |
| GET | `/logs/all` | alle Log-Einträge |
| GET | `/logs/{id}` | alle Einträge einer Anfrage |
| GET | `/logs/latest/{count}` | die letzten N Anfragen mit ihren Einträgen |
| GET | `/q/health` | Health-Check |

- `dryRun=true`: nur Entscheid, keine Weiterleitung, kein Warten auf Feedback.
- `autoConfirm=true`: Entscheid und Antwort in einem Aufruf (Feedback `OK` wird automatisch geloggt).
- Offene Anfragen verfallen nach 10 Minuten (Event `EXPIRED`). Sie gehen bei einem Neustart verloren.

Beispiel:

```bash
curl -s -H "X-API-Key: dev-key" -H "Content-Type: application/json" \
  localhost:8080/route -d '{"prompt":"Fasse die Vorteile von Glasfaser zusammen."}'
```

```json
{
  "id": "6f0c…",
  "status": "PENDING_FEEDBACK",
  "privacyLevel": "D0",
  "complexity": "mittel",
  "target": "LOCAL_MEDIUM",
  "model": "gemma3:4b",
  "reasons": {
    "reason": "complexity_local",
    "cloudProbability": 0.97,
    "piiTypes": [],
    "privacyProbabilities": {"D0": 0.97, "D1": 0.01, "D2": 0.0, "D3": 0.02},
    "complexityProbabilities": {"einfach": 0.2, "mittel": 0.7, "anspruchsvoll": 0.1}
  }
}
```

## Log

- JSONL-Datei, eine Zeile pro Ereignis: `DECISION`, `FEEDBACK`, `ANSWER`, `ERROR`, `EXPIRED`.
- Der Log enthält **keinen** Anfrage- oder Antworttext, nur Länge, Stufen, Wahrscheinlichkeiten, PII-Arten, Modelle, Token und Latenz.
- Pfad: `router.log.file` (Docker: `/data/router-log.jsonl` im Volume `router-data`).

## Konfiguration

Alle Werte stehen in `src/main/resources/application.properties`.
Jeder Wert lässt sich per Umgebungsvariable setzen (z. B. `router.routing.cloud-threshold` → `ROUTER_ROUTING_CLOUD_THRESHOLD`).

| Property | Standard | Bedeutung |
|---|---|---|
| `router.api-key.value` | `$ROUTER_API_KEY` | erwarteter API-Key |
| `router.decision.backend` | `ollama` | `ollama` oder `kev-server` |
| `router.decision.ollama.model` | `kev` | Ollama-Name des kev-Modells |
| `router.decision.ollama.num-ctx` | `8192` | Kontext des kev-Modells |
| `router.decision.ollama.reserved-tokens` | `2048` | Reserve für Prompt und Antwort; Rest = Eingabelimit |
| `router.decision.ollama.prompt-file` | – | eigener System-Prompt statt `decision-prompt.txt` |
| `router.kev.url` | `http://host.docker.internal:8009` | kev-Server |
| `router.kev.model` | `kev-latest` | Modellname im kev-Aufruf |
| `router.kev.max-input-tokens` | `8192` | darüber: lokal ohne Beurteilung |
| `router.kev.questions-file` | – | eigene Fragen-Datei statt `kev-questions.json` |
| `router.routing.cloud-allowed-levels` | `D0` | Stufen, die in die Cloud dürfen |
| `router.routing.cloud-threshold` | `0.95` | Schwelle für die Cloud |
| `router.routing.cloud-complexities` | `anspruchsvoll` | Komplexitäten, die die Cloud nutzen |
| `router.models.local-simple` / `-medium` / `-complex` | `gemma3:4b` | lokale Modelle |
| `router.models.cloud` | `gemma4:31b-cloud` | Cloud-Modell |
| `router.ollama.url` | `http://host.docker.internal:11434` | Ollama |
| `router.pending.ttl` | `10m` | Wartezeit auf Feedback |

## Prompt und Fragen

- Backend `ollama`: System-Prompt in `src/main/resources/decision-prompt.txt`.
- Backend `kev-server`: Fragen in `src/main/resources/kev-questions.json`.
Sie folgen der Richtlinie «Anleitung annotation_pilot ausfüllen».
Angepasste Kopien lassen sich mit `router.decision.ollama.prompt-file` bzw. `router.kev.questions-file` laden, ohne neuen Build.

## Aufbau

```
api/        REST-Endpunkte, API-Key-Filter, Fehlerbehandlung
decision/   Entscheidungsmodelle (Ollama, kev-Server), logprobs, Routing-Regeln (RoutingPolicy)
routing/    Ablauf (RouterService), offene Anfragen, Anhänge
llm/        LlmProvider-Schnittstelle, Ollama-Umsetzung
log/        JSONL-Log mit Index
```

Später möglich: Anthropic- und OpenAI-Adapter über `LlmProvider`, Evaluationstool für das Gold-Set, Neubewertung bei NOK.
