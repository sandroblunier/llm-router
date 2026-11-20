package ch.ace.llmrouter;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@QuarkusTestResource(WireMockResource.class)
class RouterFlowTest {

    private static final String KEY = "test-key";

    @BeforeEach
    void reset() {
        WireMockResource.server.resetAll();
        WireMockResource.server.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"model":"stub","choices":[{"message":{"role":"assistant","content":"Antwort vom LLM"}}],
                         "usage":{"prompt_tokens":12,"completion_tokens":5}}
                        """)));
    }

    private static void stubKev(double d0, double d1, double d2, double d3, String choice, double[] complexity) {
        String body = """
                {"model":"kev-latest","answers":{
                  "datenschutzstufe":{"type":"choice","choice":"%s","confidence":0.5,
                     "probabilities":{"D0":%s,"D1":%s,"D2":%s,"D3":%s}},
                  "komplexitaet":{"type":"score","score":1.0,"confidence":0.5,
                     "probabilities":{"0":%s,"1":%s,"2":%s}},
                  "pii_0":{"type":"noul","noul":%s},
                  "pii_9":{"type":"noul","noul":%s}
                },"usage":{"input_tokens":321,"output_tokens":40},"latency_ms":50}
                """.formatted(choice, d0, d1, d2, d3, complexity[0], complexity[1], complexity[2], d1 + d2, d2);
        WireMockResource.server.stubFor(post(urlEqualTo("/v1/systemone"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static final double[] DEMANDING = {0.0, 0.1, 0.9};
    private static final double[] SIMPLE = {0.9, 0.1, 0.0};

    @Test
    void missingApiKeyIsRejected() {
        given().contentType(ContentType.JSON).body("{\"prompt\":\"x\"}")
                .when().post("/route")
                .then().statusCode(401);
    }

    @Test
    void cloudEligibleRequestWaitsForFeedbackThenAnswers() {
        stubKev(0.98, 0.01, 0.0, 0.01, "D0", DEMANDING);

        String id = given().header("X-API-Key", KEY).contentType(ContentType.JSON)
                .body("{\"prompt\":\"Erkläre die Vor- und Nachteile von Kubernetes für KMU.\"}")
                .when().post("/route")
                .then().statusCode(200)
                .body("status", equalTo("PENDING_FEEDBACK"))
                .body("privacyLevel", equalTo("D0"))
                .body("complexity", equalTo("anspruchsvoll"))
                .body("target", equalTo("CLOUD"))
                .body("model", equalTo("gemma4:31b-cloud"))
                .body("reasons.reason", equalTo("cloud_eligible"))
                .body("answer", nullValue())
                .extract().path("id");

        // kev got the configured questions, Ollama not yet called
        WireMockResource.server.verify(postRequestedFor(urlEqualTo("/v1/systemone"))
                .withRequestBody(matchingJsonPath("$.questions.datenschutzstufe.type", WireMock.equalTo("choice")))
                .withRequestBody(matchingJsonPath("$.questions.komplexitaet.type", WireMock.equalTo("score")))
                .withRequestBody(matchingJsonPath("$.questions.pii_17.type", WireMock.equalTo("noul"))));
        WireMockResource.server.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));

        given().header("X-API-Key", KEY).contentType(ContentType.JSON).body("{\"feedback\":\"OK\"}")
                .when().post("/requests/" + id + "/feedback")
                .then().statusCode(200)
                .body("status", equalTo("ANSWERED"))
                .body("answer", equalTo("Antwort vom LLM"))
                .body("usage.inputTokens", equalTo(12));

        WireMockResource.server.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", WireMock.equalTo("gemma4:31b-cloud"))));

        given().header("X-API-Key", KEY).when().get("/logs/" + id)
                .then().statusCode(200)
                .body("events.type", org.hamcrest.Matchers.contains("DECISION", "FEEDBACK", "ANSWER"))
                .body("events[0].cloudProbability", notNullValue());

        // feedback twice -> 404
        given().header("X-API-Key", KEY).contentType(ContentType.JSON).body("{\"feedback\":\"OK\"}")
                .when().post("/requests/" + id + "/feedback")
                .then().statusCode(404);
    }

    @Test
    void personalDataStaysLocalAndNokAborts() {
        stubKev(0.01, 0.2, 0.78, 0.01, "D2", DEMANDING);

        String id = given().header("X-API-Key", KEY).contentType(ContentType.JSON)
                .body("{\"prompt\":\"Regula Jost leidet an Diabetes Typ 2 ...\"}")
                .when().post("/route")
                .then().statusCode(200)
                .body("privacyLevel", equalTo("D2"))
                .body("target", equalTo("LOCAL_COMPLEX"))
                .body("model", equalTo("gemma3:4b"))
                .body("reasons.piiTypes", hasItem("Name"))
                .body("reasons.piiTypes", hasItem("Gesundheit"))
                .extract().path("id");

        given().header("X-API-Key", KEY).contentType(ContentType.JSON).body("{\"feedback\":\"NOK\"}")
                .when().post("/requests/" + id + "/feedback")
                .then().statusCode(200).body("status", equalTo("ABORTED"));

        WireMockResource.server.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void cloudEligibleButSimpleUsesLocalSimple() {
        stubKev(0.99, 0.01, 0.0, 0.0, "D0", SIMPLE);
        given().header("X-API-Key", KEY).contentType(ContentType.JSON)
                .body("{\"prompt\":\"Formuliere höflicher: Schick mir das Dokument.\",\"dryRun\":true}")
                .when().post("/route")
                .then().statusCode(200)
                .body("status", equalTo("DRY_RUN"))
                .body("target", equalTo("LOCAL_SIMPLE"))
                .body("reasons.reason", equalTo("complexity_local"));
    }

    @Test
    void kevErrorFailsClosed() {
        WireMockResource.server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(500)));
        given().header("X-API-Key", KEY).contentType(ContentType.JSON)
                .body("{\"prompt\":\"Hallo\",\"dryRun\":true}")
                .when().post("/route")
                .then().statusCode(200)
                .body("target", equalTo("LOCAL_COMPLEX"))
                .body("reasons.reason", equalTo("kev_error"))
                .body("privacyLevel", nullValue());
    }

    @Test
    void kev422IsTooLong() {
        WireMockResource.server.stubFor(post(urlEqualTo("/v1/systemone")).willReturn(aResponse().withStatus(422)));
        given().header("X-API-Key", KEY).contentType(ContentType.JSON)
                .body("{\"prompt\":\"Hallo\",\"dryRun\":true}")
                .when().post("/route")
                .then().statusCode(200)
                .body("reasons.reason", equalTo("too_long"));
    }

    @Test
    void longTextIsNotSentToKev() {
        String longText = "Wort ".repeat(6000); // 30000 chars / 3.0 = 10000 tokens > 8192
        given().header("X-API-Key", KEY).contentType(ContentType.JSON)
                .body("{\"prompt\":\"" + longText + "\",\"dryRun\":true}")
                .when().post("/route")
                .then().statusCode(200)
                .body("target", equalTo("LOCAL_COMPLEX"))
                .body("reasons.reason", equalTo("too_long"));
        WireMockResource.server.verify(0, postRequestedFor(urlEqualTo("/v1/systemone")));
    }

    @Test
    void autoConfirmAnswersDirectlyAndLlmErrorGives502() {
        stubKev(0.99, 0.01, 0.0, 0.0, "D0", DEMANDING);
        given().header("X-API-Key", KEY).contentType(ContentType.JSON)
                .body("{\"prompt\":\"Frage\",\"autoConfirm\":true}")
                .when().post("/route")
                .then().statusCode(200)
                .body("status", equalTo("ANSWERED"))
                .body("answer", equalTo("Antwort vom LLM"));

        WireMockResource.server.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(500)));
        given().header("X-API-Key", KEY).contentType(ContentType.JSON)
                .body("{\"prompt\":\"Frage\",\"autoConfirm\":true}")
                .when().post("/route")
                .then().statusCode(502)
                .body("id", notNullValue());
    }

    @Test
    void multipartAttachmentIsClassifiedWithPrompt() throws Exception {
        stubKev(0.01, 0.9, 0.0, 0.09, "D1", SIMPLE);
        Path txt = Files.createTempFile("anhang", ".txt");
        Files.writeString(txt, "Kontakt: Daniel Christen, daniel.christen@kmu-beispiel.ch", StandardCharsets.UTF_8);

        given().header("X-API-Key", KEY)
                .multiPart("prompt", "Fasse den Anhang zusammen.")
                .multiPart("dryRun", "true")
                .multiPart("files", txt.toFile(), "text/plain")
                .when().post("/route")
                .then().statusCode(200)
                .body("target", equalTo("LOCAL_SIMPLE"));

        WireMockResource.server.verify(postRequestedFor(urlEqualTo("/v1/systemone"))
                .withRequestBody(matchingJsonPath("$.state", WireMock.containing("daniel.christen@kmu-beispiel.ch"))));
    }

    @Test
    void logEndpointsAndNoTextInLog() {
        stubKev(0.01, 0.9, 0.0, 0.09, "D1", SIMPLE);
        String secret = "Geheimer Text 4711";
        String id = given().header("X-API-Key", KEY).contentType(ContentType.JSON)
                .body("{\"prompt\":\"" + secret + "\",\"dryRun\":true}")
                .when().post("/route")
                .then().statusCode(200).extract().path("id");

        given().header("X-API-Key", KEY).when().get("/logs/latest/1")
                .then().statusCode(200)
                .body("", hasSize(1))
                .body("[0].id", equalTo(id));

        given().header("X-API-Key", KEY).when().get("/logs/all")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.not(containsString(secret)));

        given().header("X-API-Key", KEY).when().get("/logs/does-not-exist")
                .then().statusCode(404);
    }
}
