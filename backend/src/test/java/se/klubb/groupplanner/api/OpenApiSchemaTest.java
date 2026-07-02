package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M8 task item 3's verification: the OpenAPI schema-name collision found in M7 is actually fixed in
 * the GENERATED spec, not just in the Java source. springdoc keys {@code components.schemas} by
 * simple class name, so the two pre-M8 records both named {@code MoveRequest}
 * ({@code AssignmentController.MoveRequest \{groupId\}} vs {@code WhatIfController.MoveRequest
 * \{participantProfileId, targetGroupId, runId\}}) clobbered each other — only ONE shape survived in
 * {@code /v3/api-docs}, silently corrupting the frontend's {@code openapi-typescript} typegen.
 * After the rename both schemas must exist WITH their own distinct fields, and {@code POST
 * /api/plans/\{planId\}/solve} must expose a concrete typed response schema ({@code SolveResponse})
 * instead of the untyped {@code ResponseEntity<?>} (which produced no response schema at all).
 *
 * <p>Runs in the {@code dev} profile because that is the only profile where {@code /v3/api-docs} is
 * reachable without a token (see {@code config.ApiDocsDevExemptionIntegrationTest}) — the exact
 * same way the frontend's typegen consumes it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class OpenApiSchemaTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void bothRenamedMoveRequestSchemasExistWithTheirOwnDistinctFields() throws Exception {
        JsonNode schemas = fetchSchemas();

        JsonNode applyMove = schemas.get("ApplyMoveRequest");
        assertThat(applyMove).as("ApplyMoveRequest schema must exist").isNotNull();
        assertThat(applyMove.get("properties").has("groupId")).isTrue();
        assertThat(applyMove.get("properties").has("participantProfileId")).isFalse();

        JsonNode whatIfMove = schemas.get("WhatIfMoveRequest");
        assertThat(whatIfMove).as("WhatIfMoveRequest schema must exist").isNotNull();
        assertThat(whatIfMove.get("properties").has("participantProfileId")).isTrue();
        assertThat(whatIfMove.get("properties").has("targetGroupId")).isTrue();
        assertThat(whatIfMove.get("properties").has("runId")).isTrue();

        // The old colliding name must be GONE (its continued presence would mean some third record
        // reintroduced the collision).
        assertThat(schemas.has("MoveRequest")).isFalse();
    }

    @Test
    void solveEndpointHasATypedResponseSchema() throws Exception {
        JsonNode root = fetchRoot();
        JsonNode schemas = root.get("components").get("schemas");

        JsonNode solveResponse = schemas.get("SolveResponse");
        assertThat(solveResponse).as("SolveResponse schema must exist").isNotNull();
        for (String field : new String[] {"runId", "status", "score", "hardViolations", "feasible"}) {
            assertThat(solveResponse.get("properties").has(field)).as("SolveResponse.%s", field).isTrue();
        }

        // The solve OPERATION must actually reference it (a schema no operation points at would
        // still leave the endpoint untyped for typegen).
        JsonNode solvePost = root.get("paths").get("/api/plans/{planId}/solve").get("post");
        assertThat(solvePost).isNotNull();
        String responsesJson = solvePost.get("responses").toString();
        assertThat(responsesJson).contains("SolveResponse");
    }

    private JsonNode fetchSchemas() throws Exception {
        return fetchRoot().get("components").get("schemas");
    }

    private JsonNode fetchRoot() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }
}
