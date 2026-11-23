package ch.ace.llmrouter.evaluation;

import ch.ace.llmrouter.WireMockResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend evaluation job end to end, kev replaced by WireMock (kev-server backend). */
@QuarkusTest
@QuarkusTestResource(WireMockResource.class)
class EvaluationFlowTest {

    private static final String KEY = "test-key";

    private static String kevAnswer(String choice, double d0, double d1, double d2) {
        return """
                {"model":"kev-latest","answers":{
                  "datenschutzstufe":{"type":"choice","choice":"%s","probabilities":{"D0":%s,"D1":%s,"D2":%s,"D3":0.0}},
                  "komplexitaet":{"type":"score","probabilities":{"0":0.1,"1":0.1,"2":0.8}},
                  "pii_0":{"type":"noul","noul":%s}},
                 "usage":{"input_tokens":10}}
                """.formatted(choice, d0, d1, d2, d1 + d2);
    }

    @BeforeEach
    void stubs() {
        var wm = WireMockResource.server;
        wm.resetAll();
        // default: confident D0 -> cloud
        wm.stubFor(post(urlEqualTo("/v1/systemone")).atPriority(5)
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(kevAnswer("D0", 0.98, 0.01, 0.01))));
        // texts with "Jost" -> D2
        wm.stubFor(post(urlEqualTo("/v1/systemone")).atPriority(1).withRequestBody(containing("Jost"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(kevAnswer("D2", 0.0, 0.1, 0.9))));
        // texts with "Christen" -> kev says D0 although gold is D1 (wrong cloud)
        wm.stubFor(post(urlEqualTo("/v1/systemone")).atPriority(1).withRequestBody(containing("Christen"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(kevAnswer("D0", 0.97, 0.03, 0.0))));
    }

    private static byte[] gold() throws IOException {
        return GoldSetReaderTest.xlsx("Beschriftung", GoldSetReaderTest.HEADER,
                new Object[]{"t-01", "Vergleiche Kubernetes und Docker Swarm.", "D0", "keine", "anspruchsvoll", "nein"},
                new Object[]{"t-02", "Regula Jost leidet an Diabetes.", "D2", "Name, Gesundheit", "anspruchsvoll", "nein"},
                new Object[]{"t-03", "Kontakt: Daniel Christen", "D1", "Name", "anspruchsvoll", "ja"});
    }

    private static Response waitDone(String id) throws InterruptedException {
        Instant end = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(end)) {
            Response r = given().header("X-API-Key", KEY).get("/evaluations/" + id);
            if (!"RUNNING".equals(r.path("state"))) return r;
            Thread.sleep(100);
        }
        throw new AssertionError("evaluation did not finish");
    }

    @Test
    void runsJobAndExports() throws Exception {
        String id = given().header("X-API-Key", KEY)
                .multiPart("file", "gold.xlsx", gold(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .when().post("/evaluations")
                .then().statusCode(202)
                .body("total", equalTo(3))
                .body("mode", equalTo("backend"))
                .extract().path("id");

        Response done = waitDone(id);
        done.then().statusCode(200)
                .body("state", equalTo("DONE"))
                .body("done", equalTo(3))
                .body("rows[0].nr", equalTo("t-01"))
                .body("rows[0].predicted.target", equalTo("CLOUD"))
                .body("rows[1].predicted.privacyLevel", equalTo("D2"))
                .body("rows[1].predicted.target", equalTo("LOCAL_COMPLEX"))
                .body("summary.wrongCloud", equalTo(1))
                .body("summary.wrongCloudNrs[0]", equalTo("t-03"))
                .body("summary.sensitiveTotal", equalTo(2))
                .body(not(containsString("Diabetes")));

        // each row was classified on its own
        WireMockResource.server.verify(3, postRequestedFor(urlEqualTo("/v1/systemone")));

        // SSE of a finished job: status, replayed rows, done
        String sse = given().header("X-API-Key", KEY).accept("text/event-stream")
                .get("/evaluations/" + id + "/events").then().statusCode(200).extract().asString();
        assertTrue(sse.contains("\"type\":\"row\""), sse);
        assertTrue(sse.contains("\"type\":\"done\""), sse);

        String csv = new String(given().header("X-API-Key", KEY).get("/evaluations/" + id + "/export?format=csv")
                .then().statusCode(200)
                .header("Content-Disposition", containsString("evaluation_gold_"))
                .extract().asByteArray(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("t-03;D1;Name;anspruchsvoll;ja;D0;anspruchsvoll;CLOUD"), csv);
        assertEquals(4, csv.strip().split("\r\n").length);

        given().header("X-API-Key", KEY).get("/evaluations/" + id + "/export?format=json")
                .then().statusCode(200)
                .body("meta.fileName", equalTo("gold.xlsx"))
                .body("summary.total", equalTo(3))
                .body("rows.size()", equalTo(3));
    }

    @Test
    void rejectsBadFilesAndUnknownIds() throws IOException {
        given().header("X-API-Key", KEY)
                .multiPart("file", "x.xlsx", GoldSetReaderTest.xlsx("Tabelle1", GoldSetReaderTest.HEADER), "application/octet-stream")
                .when().post("/evaluations")
                .then().statusCode(400).body("error", containsString("Beschriftung"));
        given().header("X-API-Key", KEY).get("/evaluations/does-not-exist").then().statusCode(404);
        given().get("/evaluations/does-not-exist").then().statusCode(401);
    }
}
