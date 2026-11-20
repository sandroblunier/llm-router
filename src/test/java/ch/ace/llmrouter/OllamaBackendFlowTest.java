package ch.ace.llmrouter;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/** Flow with the default backend: kev served by Ollama (/api/chat with JSON schema and logprobs). */
@QuarkusTest
@TestProfile(OllamaBackendFlowTest.OllamaBackend.class)
class OllamaBackendFlowTest {

    public static class OllamaBackend implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("router.decision.backend", "ollama",
                    "router.decision.ollama.model", "kev-test");
        }
    }

    private static final String KEY = "test-key";

    @BeforeEach
    void reset() {
        WireMockResource.server.resetAll();
    }

    /** Ollama /api/chat answer whose privacy value is one token with the given D0 probability. */
    private static void stubDecision(String level, double pD0, String piiJson, String complexity) {
        String content = "{\\\"datenschutzstufe\\\":\\\"" + level + "\\\",\\\"pii_arten\\\":" + piiJson.replace("\"", "\\\"")
                + ",\\\"komplexitaet\\\":\\\"" + complexity + "\\\"}";
        double pOther = 1.0 - pD0;
        String body = """
                {"model":"kev-test","message":{"role":"assistant","content":"%s"},
                 "prompt_eval_count":900,"eval_count":30,
                 "logprobs":[
                   {"token":"{\\"","logprob":0.0,"top_logprobs":[]},
                   {"token":"datenschutzstufe","logprob":0.0,"top_logprobs":[]},
                   {"token":"\\":\\"","logprob":0.0,"top_logprobs":[]},
                   {"token":"%s","logprob":-0.01,"top_logprobs":[
                      {"token":"D0","logprob":%s},{"token":"D1","logprob":%s}]},
                   {"token":"\\",","logprob":0.0,"top_logprobs":[]}
                 ]}
                """.formatted(content, level, Math.log(pD0), Math.log(pOther));
        WireMockResource.server.stubFor(post(urlEqualTo("/api/chat"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void confidentD0DemandingGoesToCloud() {
        stubDecision("D0", 0.98, "[]", "anspruchsvoll");
        Number cloudProbability = given().header("X-API-Key", KEY).contentType(ContentType.JSON)
                .body("{\"prompt\":\"Vergleiche Kubernetes und Docker Swarm.\",\"dryRun\":true}")
                .when().post("/route")
                .then().statusCode(200)
                .body("privacyLevel", equalTo("D0"))
                .body("target", equalTo("CLOUD"))
                .extract().path("reasons.cloudProbability");
        org.junit.jupiter.api.Assertions.assertEquals(0.98, cloudProbability.doubleValue(), 1e-6);

        WireMockResource.server.verify(postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.model", WireMock.equalTo("kev-test")))
                .withRequestBody(matchingJsonPath("$.logprobs", WireMock.equalTo("true")))
                .withRequestBody(matchingJsonPath("$.format.properties.datenschutzstufe"))
                .withRequestBody(matchingJsonPath("$.options.temperature", WireMock.equalTo("0"))));
    }

    @Test
    void uncertainD0StaysLocal() {
        stubDecision("D0", 0.80, "[]", "anspruchsvoll");
        given().header("X-API-Key", KEY).contentType(ContentType.JSON)
                .body("{\"prompt\":\"Etwas\",\"dryRun\":true}")
                .when().post("/route")
                .then().statusCode(200)
                .body("privacyLevel", equalTo("D0"))
                .body("target", equalTo("LOCAL_COMPLEX"))
                .body("reasons.reason", equalTo("privacy_below_threshold"));
    }

    @Test
    void piiTypesFromAnswerAreReported() {
        stubDecision("D1", 0.01, "[\"Name\",\"E-Mail\"]", "einfach");
        given().header("X-API-Key", KEY).contentType(ContentType.JSON)
                .body("{\"prompt\":\"Mail an Daniel Christen\",\"dryRun\":true}")
                .when().post("/route")
                .then().statusCode(200)
                .body("target", equalTo("LOCAL_SIMPLE"))
                .body("reasons.piiTypes", hasItem("Name"))
                .body("reasons.piiTypes", hasItem("E-Mail"));
    }

    @Test
    void missingLogprobsFailsClosed() {
        WireMockResource.server.stubFor(post(urlEqualTo("/api/chat"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"model":"kev-test","message":{"role":"assistant",
                         "content":"{\\"datenschutzstufe\\":\\"D0\\",\\"pii_arten\\":[],\\"komplexitaet\\":\\"anspruchsvoll\\"}"},
                         "prompt_eval_count":100,"eval_count":20}
                        """)));
        given().header("X-API-Key", KEY).contentType(ContentType.JSON)
                .body("{\"prompt\":\"Etwas\",\"dryRun\":true}")
                .when().post("/route")
                .then().statusCode(200)
                .body("target", equalTo("LOCAL_COMPLEX"))
                .body("reasons.reason", equalTo("kev_error"));
    }

    @Test
    void filledContextCountsAsTooLong() {
        WireMockResource.server.stubFor(post(urlEqualTo("/api/chat"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"model":"kev-test","message":{"role":"assistant","content":"{}"},
                         "prompt_eval_count":8180,"eval_count":20}
                        """)));
        given().header("X-API-Key", KEY).contentType(ContentType.JSON)
                .body("{\"prompt\":\"Etwas\",\"dryRun\":true}")
                .when().post("/route")
                .then().statusCode(200)
                .body("reasons.reason", equalTo("too_long"));
    }
}
