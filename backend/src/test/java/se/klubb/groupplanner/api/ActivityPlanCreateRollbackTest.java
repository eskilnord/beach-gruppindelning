package se.klubb.groupplanner.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.resources.DefaultTimeSlotService;
import se.klubb.groupplanner.util.Uuid7;

/**
 * MAJOR review fix (B3, v0.6.0): the {@code @Transactional} wiring on {@code
 * ActivityPlanController.create} was correct by inspection - {@link DefaultTimeSlotService} is an
 * injected bean called from outside the class, so Spring's transactional proxy genuinely wraps the
 * call, unlike a same-class self-invocation which would silently bypass it - but untested. This
 * class pins the rollback boundary: {@code DefaultTimeSlotService} is replaced with a mock that
 * throws, and the test asserts the {@code ActivityPlan} INSERT that already ran earlier in the same
 * transaction is rolled back with it, not left as an orphaned "created" plan with no time slots.
 *
 * <p>A separate top-level test class (rather than a {@code @MockitoBean} added to {@code
 * ActivityPlanControllerTest}) deliberately: {@code @MockitoBean} replaces the bean for every test
 * in the Spring context it's declared in, which would break every other test in that class that
 * relies on real seeding behavior (e.g. {@code createSeedsDefaultThursdayTimeSlotsByDefault}). A
 * dedicated class gets its own cached Spring context with only this one override.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityPlanCreateRollbackTest {

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

    @MockitoBean
    private DefaultTimeSlotService defaultTimeSlotService;

    private String createSeason() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(
                new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        return season.id();
    }

    @Test
    void seedingFailureRollsBackThePlanInsert() throws Exception {
        when(defaultTimeSlotService.seedDefaults(any())).thenThrow(new RuntimeException("boom: seeding failed"));

        String seasonId = createSeason();
        String createBody = objectMapper.writeValueAsString(
                new ActivityPlanController.CreateActivityPlanRequest("Herr", null, null, null, null, null, null, null));

        mockMvc.perform(post("/api/seasons/" + seasonId + "/plans")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is5xxServerError());

        // The plan INSERT ran earlier in the same @Transactional method, before seeding threw - if
        // the rollback boundary is working, it must not have survived the exception.
        assertThat(activityPlanRepository.findBySeasonPlanId(seasonId)).isEmpty();
    }
}
