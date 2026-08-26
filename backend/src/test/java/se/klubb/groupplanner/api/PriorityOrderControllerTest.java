package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.fields.ConstraintWeightOverrideRequest;
import se.klubb.groupplanner.fields.ConstraintWeightService;
import se.klubb.groupplanner.fields.HardOrSoft;
import se.klubb.groupplanner.fields.PriorityOrder;
import se.klubb.groupplanner.fields.PriorityOrder.Priority;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * v0.6.0 milestone B7: {@code GET|PUT /api/plans/{planId}/priority-order}. Mirrors {@link
 * ConstraintWeightControllerTest}'s conventions (mock-token header, temp-dir SQLite per test class).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PriorityOrderControllerTest {

    private static final String VALID_TOKEN = "test-secret-token";

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;
    @Autowired
    private OptimizationRunRepository optimizationRunRepository;
    @Autowired
    private ConstraintWeightService constraintWeightService;
    @Autowired
    private JdbcClient jdbcClient;

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, null, now, now));
        return plan.id();
    }

    private String finishedRun(String planId) {
        Instant now = Instant.now();
        int revision = activityPlanRepository.getPlanRevision(planId);
        OptimizationRun run = new OptimizationRun(
                Uuid7.generate(), planId, "{}", "{}", "0hard/0medium/0soft", OptimizationRun.STATUS_FINISHED,
                now.toString(), now.toString(), 0, "{}", revision);
        return optimizationRunRepository.insert(run).id();
    }

    // ─────────────────────────────────────────────────────────────────────── GET, fresh plan

    @Test
    void getOnFreshPlanReturnsDefaultsMatchingAndUnstamped() throws Exception {
        String planId = createPlan();

        String response = mockMvc.perform(get("/api/plans/" + planId + "/priority-order").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchesOrder").value(true))
                .andExpect(jsonPath("$.customWeightsActive").value(false))
                .andExpect(jsonPath("$.otherOverridesActive").value(false))
                .andExpect(jsonPath("$.staleSinceLastRun").value(false))
                .andExpect(jsonPath("$.updatedAt").doesNotExist())
                .andExpect(jsonPath("$.priorities.length()").value(4))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertOrder(json.get("order"), "TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME", "LEVEL");
        assertOrder(json.get("defaultOrder"), "TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME", "LEVEL");
        JsonNode trainTogether = findByKey(json, "TRAIN_TOGETHER");
        assertThat(trainTogether.get("rank").asInt()).isEqualTo(1);
        assertThat(trainTogether.get("labelSv").asText()).isEqualTo("Träna tillsammans");
        assertThat(trainTogether.get("summarySv").asText())
                .isEqualTo("Spelare som önskat varandra hamnar i samma grupp. Det väger tyngst av allt.");
        assertThat(trainTogether.get("constraintKeys")).extracting(JsonNode::asText)
                .containsExactly("sameGroupSoft", "differentGroupSoft");
        assertThat(trainTogether.get("weights").get("sameGroupSoft").asInt()).isEqualTo(2400);
        assertThat(trainTogether.get("weights").get("differentGroupSoft").asInt()).isEqualTo(1800);
        assertThat(trainTogether.get("enabled").asBoolean()).isTrue();

        JsonNode previousGroup = findByKey(json, "PREVIOUS_GROUP");
        assertThat(previousGroup.get("rank").asInt()).isEqualTo(2);
        assertThat(previousGroup.get("summarySv").asText())
                .isEqualTo("Spelare får fortsätta i sin tidigare grupp när det går.");

        JsonNode preferredTime = findByKey(json, "PREFERRED_TIME");
        assertThat(preferredTime.get("rank").asInt()).isEqualTo(3);
        assertThat(preferredTime.get("summarySv").asText())
                .isEqualTo("Önskad träningstid uppfylls när det inte krockar med viktigare önskemål.");

        JsonNode level = findByKey(json, "LEVEL");
        assertThat(level.get("rank").asInt()).isEqualTo(4);
        assertThat(level.get("summarySv").asText()).isEqualTo("Grupperna hålls jämna i nivå – vägs in sist.");
        assertThat(level.get("weights").get("levelBalance").asInt()).isEqualTo(85);
        assertThat(level.get("weights").get("groupOrderByLevel").asInt()).isEqualTo(42);
        assertThat(level.get("enabled").asBoolean()).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────── PUT round-trip, all 24 permutations

    @Test
    void allTwentyFourPermutationsRoundTrip() throws Exception {
        String planId = createPlan();
        List<List<Priority>> permutations = allPermutations();
        assertThat(permutations).hasSize(24);

        for (List<Priority> order : permutations) {
            List<String> names = order.stream().map(Priority::name).toList();
            String body = objectMapper.writeValueAsString(Map.of("order", names));

            String putResponse = mockMvc.perform(put("/api/plans/" + planId + "/priority-order")
                            .header("X-GP-Token", VALID_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.matchesOrder").value(true))
                    .andExpect(jsonPath("$.customWeightsActive").value(false))
                    .andReturn().getResponse().getContentAsString();
            JsonNode putJson = objectMapper.readTree(putResponse);
            assertOrder(putJson.get("order"), names.toArray(new String[0]));
            assertWeightsMatch(putJson, order);

            String getResponse = mockMvc.perform(get("/api/plans/" + planId + "/priority-order").header("X-GP-Token", VALID_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.matchesOrder").value(true))
                    .andReturn().getResponse().getContentAsString();
            JsonNode getJson = objectMapper.readTree(getResponse);
            assertOrder(getJson.get("order"), names.toArray(new String[0]));
            assertWeightsMatch(getJson, order);
        }
    }

    private static void assertWeightsMatch(JsonNode response, List<Priority> order) {
        Map<String, Integer> expected = PriorityOrder.weightsFor(order);
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            boolean found = false;
            for (JsonNode priority : response.get("priorities")) {
                JsonNode weight = priority.get("weights").get(entry.getKey());
                if (weight != null) {
                    found = true;
                    assertThat(weight.asInt())
                            .as("weight for %s", entry.getKey())
                            .isEqualTo(entry.getValue());
                }
            }
            assertThat(found).as("no priority row exposed weight for %s", entry.getKey()).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────────────────── PUT invalid

    @Test
    void putWithThreeEntriesIsRejected() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(
                Map.of("order", List.of("TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME")));

        mockMvc.perform(put("/api/plans/" + planId + "/priority-order")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        assertUnchangedDefaults(planId);
    }

    @Test
    void putWithFiveEntriesIsRejected() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(Map.of(
                "order", List.of("TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME", "LEVEL", "LEVEL")));

        mockMvc.perform(put("/api/plans/" + planId + "/priority-order")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        assertUnchangedDefaults(planId);
    }

    @Test
    void putWithDuplicateEntryIsRejected() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(Map.of(
                "order", List.of("TRAIN_TOGETHER", "TRAIN_TOGETHER", "PREFERRED_TIME", "LEVEL")));

        mockMvc.perform(put("/api/plans/" + planId + "/priority-order")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        assertUnchangedDefaults(planId);
    }

    @Test
    void putWithUnknownPriorityKeyIsRejected() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(Map.of(
                "order", List.of("TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME", "NOT_A_PRIORITY")));

        mockMvc.perform(put("/api/plans/" + planId + "/priority-order")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("NOT_A_PRIORITY")));

        assertUnchangedDefaults(planId);
    }

    private void assertUnchangedDefaults(String planId) throws Exception {
        String response = mockMvc.perform(get("/api/plans/" + planId + "/priority-order").header("X-GP-Token", VALID_TOKEN))
                .andExpect(jsonPath("$.matchesOrder").value(true))
                .andReturn().getResponse().getContentAsString();
        assertOrder(objectMapper.readTree(response).get("order"), "TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME", "LEVEL");
    }

    /** {@code jsonPath(...).value(List.of(...))} does not reliably compare against a JSON array via
     * the jayway JsonPath provider used here - reading the array as a {@link JsonNode} and comparing
     * elements directly is the robust way (matches this codebase's existing {@code jsonPath("$[0]...")}
     * index-based convention for array assertions). */
    private static void assertOrder(JsonNode orderArray, String... expected) {
        assertThat(orderArray).as("order array must be present").isNotNull();
        List<String> actual = new ArrayList<>();
        orderArray.forEach(node -> actual.add(node.asText()));
        assertThat(actual).containsExactly(expected);
    }

    // ─────────────────────────────────────────────────────────────────────── customWeightsActive / inference

    @Test
    void manualWeightOverrideMakesMatchesOrderFalseAndInfersPlausibleOrder() throws Exception {
        String planId = createPlan();

        // Bump sameGroupSoft (TRAIN_TOGETHER's representative marginal) far above every ladder rank,
        // so TRAIN_TOGETHER must be inferred as rank 1 regardless of the other 3 (still at their
        // default-order values: PREVIOUS_GROUP=1500 > PREFERRED_TIME=950 > LEVEL 85*7=595).
        constraintWeightService.applyOverrides(
                planId, List.of(new ConstraintWeightOverrideRequest("sameGroupSoft", null, 9999, null)));

        String response = mockMvc.perform(get("/api/plans/" + planId + "/priority-order").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchesOrder").value(false))
                .andExpect(jsonPath("$.customWeightsActive").value(true))
                .andExpect(jsonPath("$.order[0]").value("TRAIN_TOGETHER"))
                .andExpect(jsonPath("$.order[1]").value("PREVIOUS_GROUP"))
                .andExpect(jsonPath("$.order[2]").value("PREFERRED_TIME"))
                .andExpect(jsonPath("$.order[3]").value("LEVEL"))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(findByKey(json, "TRAIN_TOGETHER").get("weights").get("sameGroupSoft").asInt()).isEqualTo(9999);
    }

    // ─────────────────────────────────────────────────────────────────────── otherOverridesActive

    @Test
    void overridingANonBucketConstraintSetsOtherOverridesActiveButLeavesMatchesOrderTrue() throws Exception {
        String planId = createPlan();

        constraintWeightService.applyOverrides(
                planId, List.of(new ConstraintWeightOverrideRequest("groupSizeTarget", null, 111, null)));

        mockMvc.perform(get("/api/plans/" + planId + "/priority-order").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otherOverridesActive").value(true))
                .andExpect(jsonPath("$.matchesOrder").value(true));
    }

    // ─────────────────────────────────────────────────────────────────────── revision bump

    @Test
    void putBumpsThePlanRevision() throws Exception {
        String planId = createPlan();
        int before = activityPlanRepository.getPlanRevision(planId);

        String body = objectMapper.writeValueAsString(
                Map.of("order", List.of("PREFERRED_TIME", "TRAIN_TOGETHER", "LEVEL", "PREVIOUS_GROUP")));
        mockMvc.perform(put("/api/plans/" + planId + "/priority-order")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        int after = activityPlanRepository.getPlanRevision(planId);
        assertThat(after).isGreaterThan(before);
    }

    // ─────────────────────────────────────────────────────────────────────── staleSinceLastRun

    @Test
    void changingOrderAfterARunMakesItStale() throws Exception {
        String planId = createPlan();
        finishedRun(planId);

        // Fresh right after the run (no plan mutation since).
        mockMvc.perform(get("/api/plans/" + planId + "/priority-order").header("X-GP-Token", VALID_TOKEN))
                .andExpect(jsonPath("$.staleSinceLastRun").value(false));

        String body = objectMapper.writeValueAsString(
                Map.of("order", List.of("LEVEL", "PREFERRED_TIME", "PREVIOUS_GROUP", "TRAIN_TOGETHER")));
        mockMvc.perform(put("/api/plans/" + planId + "/priority-order")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/plans/" + planId + "/priority-order").header("X-GP-Token", VALID_TOKEN))
                .andExpect(jsonPath("$.staleSinceLastRun").value(true));
    }

    @Test
    void freshOrderFollowedByANewRunIsNotStale() throws Exception {
        String planId = createPlan();

        String body = objectMapper.writeValueAsString(
                Map.of("order", List.of("LEVEL", "PREFERRED_TIME", "PREVIOUS_GROUP", "TRAIN_TOGETHER")));
        mockMvc.perform(put("/api/plans/" + planId + "/priority-order")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // A run started AFTER the order change captures the plan's revision as of right now, so the
        // plan is not stale relative to it.
        finishedRun(planId);

        mockMvc.perform(get("/api/plans/" + planId + "/priority-order").header("X-GP-Token", VALID_TOKEN))
                .andExpect(jsonPath("$.staleSinceLastRun").value(false));
    }

    /** B7 review MINOR fix: a FAILED (or still-SOLVING) run's {@link OptimizationRun#planRevision}
     * is always 0 (never written back — see that record's javadoc), NOT a real "basedOnRevision".
     * Before the fix, {@code isStaleSinceLastRun} compared against the latest run of ANY status, so
     * once such a run became "the plan's latest run", {@code staleSinceLastRun} was pinned {@code
     * true} forever afterwards (current revision, always &gt;= 1 after any mutation, could never
     * equal 0 again) — even with a perfectly fresh plan and no successful run yet to be stale
     * relative to. The fix ({@code findLatestFinishedByActivityPlanId}) ignores non-FINISHED runs
     * entirely, so a FAILED run alone (no FINISHED run at all yet) must report {@code false} (nothing
     * to be stale relative to), exactly like the "no run at all" case. */
    @Test
    void aFailedRunAloneDoesNotPermanentlyPinStaleSinceLastRunTrue() throws Exception {
        String planId = createPlan();

        // A mutation the plan revision, mirroring a real "edit weights, then start a solve" flow.
        constraintWeightService.applyOverrides(
                planId, List.of(new ConstraintWeightOverrideRequest("sameGroupSoft", null, 2500, null)));
        assertThat(activityPlanRepository.getPlanRevision(planId)).isGreaterThan(0);

        // Mirrors se.klubb.groupplanner.solver.run.OptimizationRunService#startRun (planRevision 0)
        // then #failRun (status FAILED, planRevision left at 0 — never written back).
        Instant now = Instant.now();
        OptimizationRun failed = new OptimizationRun(
                Uuid7.generate(), planId, "{}", "{}", null, OptimizationRun.STATUS_FAILED,
                now.toString(), now.toString(), 0, "{\"error\":\"boom\"}", 0);
        optimizationRunRepository.insert(failed);

        mockMvc.perform(get("/api/plans/" + planId + "/priority-order").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staleSinceLastRun").value(false));
    }

    // ─────────────────────────────────────────────────────────────────────── disabled bucket constraint

    @Test
    void disablingABucketConstraintMakesMatchesOrderFalse() throws Exception {
        String planId = createPlan();

        constraintWeightService.applyOverrides(
                planId, List.of(new ConstraintWeightOverrideRequest("timePreferenceSoft", null, null, false)));

        mockMvc.perform(get("/api/plans/" + planId + "/priority-order").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchesOrder").value(false))
                .andExpect(jsonPath("$.customWeightsActive").value(true));
    }

    /** B7 review fix (inference lied about disabled keys): a disabled {@code timePreferenceSoft} used
     * to still report its full configured weight (2400, well above every OTHER rank's default value)
     * and get inferred as rank 1 "väger tyngst av allt" — for a constraint the solver ignores entirely.
     * It must instead sink to LAST (weight 0 always loses the descending sort, default-order tiebreak
     * then places it after every still-live priority), report weight 0 for {@code timePreferenceSoft}
     * in its own row, and report {@code enabled=false} on that row so the frontend can grey it out. */
    @Test
    void disabledBucketKeySinksToLastInInferredOrderAndIsReportedAsZeroWeightAndDisabled() throws Exception {
        String planId = createPlan();

        constraintWeightService.applyOverrides(
                planId, List.of(new ConstraintWeightOverrideRequest("timePreferenceSoft", null, 9999, false)));

        String response = mockMvc.perform(get("/api/plans/" + planId + "/priority-order").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchesOrder").value(false))
                .andExpect(jsonPath("$.order[3]").value("PREFERRED_TIME"))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        JsonNode preferredTime = findByKey(json, "PREFERRED_TIME");
        assertThat(preferredTime.get("rank").asInt()).isEqualTo(4);
        assertThat(preferredTime.get("weights").get("timePreferenceSoft").asInt()).isEqualTo(0);
        assertThat(preferredTime.get("enabled").asBoolean()).isFalse();

        // Every still-enabled row must report enabled=true.
        assertThat(findByKey(json, "TRAIN_TOGETHER").get("enabled").asBoolean()).isTrue();
        assertThat(findByKey(json, "PREVIOUS_GROUP").get("enabled").asBoolean()).isTrue();
        assertThat(findByKey(json, "LEVEL").get("enabled").asBoolean()).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────── PUT restores disabled/reclassified keys

    /** B7 review BLOCKER fix: {@code PUT} must restore {@code enabled=true}/{@code hardOrSoft=SOFT}
     * on all 6 bucket keys, not just their weight. Before the fix, a previously disabled or
     * HARD-reclassified bucket key stayed broken forever after a PUT — {@code applyOverrides} treats
     * a {@code null} field as "keep current value", so only the weight would change, {@code
     * matchesOrder} would still report {@code false} (it requires {@code enabled && SOFT} for every
     * bucket key), and there is no way to fix it back up through this endpoint, since PUT never lets
     * the caller touch {@code hardOrSoft}/{@code enabled} directly. */
    @Test
    void putRestoresADisabledAndAHardReclassifiedBucketKeyToEnabledSoftAtLadderWeights() throws Exception {
        String planId = createPlan();

        constraintWeightService.applyOverrides(planId, List.of(
                new ConstraintWeightOverrideRequest("timePreferenceSoft", null, null, false),
                new ConstraintWeightOverrideRequest("sameGroupSoft", HardOrSoft.HARD, null, null)));

        String body = objectMapper.writeValueAsString(
                Map.of("order", List.of("TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME", "LEVEL")));
        String putResponse = mockMvc.perform(put("/api/plans/" + planId + "/priority-order")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchesOrder").value(true))
                .andExpect(jsonPath("$.customWeightsActive").value(false))
                .andReturn().getResponse().getContentAsString();

        JsonNode putJson = objectMapper.readTree(putResponse);
        assertThat(findByKey(putJson, "TRAIN_TOGETHER").get("enabled").asBoolean()).isTrue();
        assertThat(findByKey(putJson, "PREFERRED_TIME").get("enabled").asBoolean()).isTrue();
        assertThat(findByKey(putJson, "TRAIN_TOGETHER").get("weights").get("sameGroupSoft").asInt()).isEqualTo(2400);
        assertThat(findByKey(putJson, "PREFERRED_TIME").get("weights").get("timePreferenceSoft").asInt()).isEqualTo(950);

        mockMvc.perform(get("/api/plans/" + planId + "/priority-order").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchesOrder").value(true))
                .andExpect(jsonPath("$.customWeightsActive").value(false));
    }

    // ─────────────────────────────────────────────────────────────────────── updatedAt

    @Test
    void updatedAtIsSetAfterAPutAndReflectsTheBucketOverrideRows() throws Exception {
        String planId = createPlan();

        mockMvc.perform(get("/api/plans/" + planId + "/priority-order").header("X-GP-Token", VALID_TOKEN))
                .andExpect(jsonPath("$.updatedAt").doesNotExist());

        String body = objectMapper.writeValueAsString(
                Map.of("order", List.of("PREFERRED_TIME", "TRAIN_TOGETHER", "LEVEL", "PREVIOUS_GROUP")));
        mockMvc.perform(put("/api/plans/" + planId + "/priority-order")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").exists());

        mockMvc.perform(get("/api/plans/" + planId + "/priority-order").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").exists())
                // Putting defaultOrder's own values must still leave matchesOrder true, even though
                // override rows now exist at exactly the default values (matchesOrder is about
                // VALUES, not about whether override rows exist).
                .andExpect(jsonPath("$.matchesOrder").value(true));
    }

    @Test
    void puttingTheDefaultOrderResultsInMatchesOrderTrueWithUpdatedAtSet() throws Exception {
        String planId = createPlan();
        String body = objectMapper.writeValueAsString(
                Map.of("order", List.of("TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME", "LEVEL")));

        mockMvc.perform(put("/api/plans/" + planId + "/priority-order")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchesOrder").value(true))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    /** B7 review MINOR fix: an override row written before V14 (or by any future path that skips
     * {@link se.klubb.groupplanner.repo.ConstraintWeightConfigRepository#upsert}) has a NULL {@code
     * updated_at} — this must not blow up {@link
     * se.klubb.groupplanner.fields.PriorityOrderService} (it did not before either, but there was no
     * test pinning the behavior), and {@code matchesOrder} must still be computed purely from
     * weight/hardOrSoft/enabled, never from {@code updatedAt}. Bypasses the repository (raw insert)
     * to construct exactly this legacy row shape. */
    @Test
    void legacyOverrideRowWithNullUpdatedAtReportsNullUpdatedAtAndStillComputesMatchesOrderCorrectly() throws Exception {
        String planId = createPlan();

        jdbcClient.sql("""
                        INSERT INTO constraint_weight_config (id, activity_plan_id, constraint_key, hard_or_soft, weight, enabled)
                        VALUES (:id, :activityPlanId, :constraintKey, :hardOrSoft, :weight, :enabled)
                        """)
                .param("id", Uuid7.generate())
                .param("activityPlanId", planId)
                .param("constraintKey", "sameGroupSoft")
                .param("hardOrSoft", HardOrSoft.SOFT)
                .param("weight", 2400)
                .param("enabled", 1)
                .update();

        mockMvc.perform(get("/api/plans/" + planId + "/priority-order").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").doesNotExist())
                // The legacy row's value (2400) is exactly TRAIN_TOGETHER's rank-1 ladder value, so
                // the plan still matches the default order despite the row predating updated_at.
                .andExpect(jsonPath("$.matchesOrder").value(true));
    }

    // ─────────────────────────────────────────────────────────────────────── helpers

    private static List<List<Priority>> allPermutations() {
        List<List<Priority>> result = new ArrayList<>();
        permute(new ArrayList<>(List.of(Priority.values())), 0, result);
        return result;
    }

    private static void permute(List<Priority> arr, int k, List<List<Priority>> result) {
        if (k == arr.size()) {
            result.add(List.copyOf(arr));
            return;
        }
        for (int i = k; i < arr.size(); i++) {
            Collections.swap(arr, k, i);
            permute(arr, k + 1, result);
            Collections.swap(arr, k, i);
        }
    }

    private static JsonNode findByKey(JsonNode response, String key) {
        for (JsonNode priority : response.get("priorities")) {
            if (key.equals(priority.get("key").asText())) {
                return priority;
            }
        }
        throw new AssertionError("No priority row for key " + key);
    }
}
