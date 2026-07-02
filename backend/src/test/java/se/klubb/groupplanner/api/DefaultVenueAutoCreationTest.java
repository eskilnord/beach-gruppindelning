package se.klubb.groupplanner.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Isolated (own {@code @TempDir} -&gt; own Spring context/DB) test of the "auto-create a default
 * venue named 'Hallen' the first time a court is needed and none exists yet" rule (spec §12.2,
 * docs/plan.md M5 row). Deliberately its own test class rather than a method in {@code
 * VenueCourtControllerTest}: every test method within one {@code @SpringBootTest} class shares the
 * same DB (one context, keyed by the static {@code @TempDir}), so "no venue exists yet" can only be
 * asserted meaningfully in a class where no other test method has already created one.
 * {@code @TestMethodOrder} pins execution order since the two methods build on each other's state.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DefaultVenueAutoCreationTest {

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

    private static String defaultVenueId;

    @Test
    @Order(1)
    void firstCourtCreatedWithoutVenueIdAutoCreatesHallenAsTheOnlyVenue() throws Exception {
        mockMvc.perform(get("/api/venues").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        String courtBody = objectMapper.writeValueAsString(new CourtController.CreateCourtRequest(null, "Bana 1", null, null));
        String response = mockMvc.perform(post("/api/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(courtBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();
        defaultVenueId = objectMapper.readTree(response).get("venueId").asText();

        mockMvc.perform(get("/api/venues/" + defaultVenueId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Hallen"));

        mockMvc.perform(get("/api/venues").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @Order(2)
    void secondCourtWithoutVenueIdReusesTheSameDefaultVenueInsteadOfCreatingAnotherHallen() throws Exception {
        String courtBody2 = objectMapper.writeValueAsString(new CourtController.CreateCourtRequest(null, "Bana 2", null, null));
        mockMvc.perform(post("/api/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(courtBody2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.venueId").value(defaultVenueId));

        mockMvc.perform(get("/api/venues").header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].courts.length()").value(2));
    }
}
