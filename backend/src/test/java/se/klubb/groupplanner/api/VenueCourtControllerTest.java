package se.klubb.groupplanner.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MockMvc CRUD tests for {@code /api/venues} (spec §7.7) and {@code /api/courts} (spec §6.8/§7.8).
 * The "auto-create default venue when none exists yet" rule needs a pristine, venue-free database to
 * assert meaningfully, so it lives in its own isolated {@link DefaultVenueAutoCreationTest} class
 * (separate {@code @TempDir} -&gt; separate Spring context/DB, per this class's own test methods
 * sharing one context/DB — see that class's Javadoc). Every test here that needs a court therefore
 * creates its own explicit venue rather than relying on which venue happens to be "first".
 */
@SpringBootTest
@AutoConfigureMockMvc
class VenueCourtControllerTest {

    private static final String VALID_TOKEN = "test-secret-token";
    private static final AtomicInteger NAME_COUNTER = new AtomicInteger();

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

    private String createVenue(String name) throws Exception {
        String body = objectMapper.writeValueAsString(new VenueController.CreateVenueRequest(name, null));
        String response = mockMvc.perform(post("/api/venues")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    @Test
    void venueCrudHappyPathWithNestedCourts() throws Exception {
        String body = objectMapper.writeValueAsString(new VenueController.CreateVenueRequest("Sporthallen", "Huvudhallen"));
        String response = mockMvc.perform(post("/api/venues")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Sporthallen"))
                .andExpect(jsonPath("$.courts").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String venueId = objectMapper.readTree(response).get("id").asText();

        String courtBody = objectMapper.writeValueAsString(new CourtController.CreateCourtRequest(venueId, "Bana A", true, null));
        mockMvc.perform(post("/api/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(courtBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.venueId").value(venueId));

        mockMvc.perform(get("/api/venues/" + venueId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courts.length()").value(1))
                .andExpect(jsonPath("$.courts[0].name").value("Bana A"));

        mockMvc.perform(patch("/api/venues/" + venueId)
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Ny hall\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ny hall"));
    }

    @Test
    void duplicateCourtNameInSameVenueReturns409() throws Exception {
        String venueId = createVenue("Venue for duplicate-name test " + NAME_COUNTER.incrementAndGet());
        String courtBody = objectMapper.writeValueAsString(new CourtController.CreateCourtRequest(venueId, "Bana 1", null, null));
        mockMvc.perform(post("/api/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(courtBody))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(courtBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void sameCourtNameInDifferentVenuesIsAllowed() throws Exception {
        String venueA = createVenue("Venue A " + NAME_COUNTER.incrementAndGet());
        String venueB = createVenue("Venue B " + NAME_COUNTER.incrementAndGet());
        String courtBodyA = objectMapper.writeValueAsString(new CourtController.CreateCourtRequest(venueA, "Bana 1", null, null));
        String courtBodyB = objectMapper.writeValueAsString(new CourtController.CreateCourtRequest(venueB, "Bana 1", null, null));

        mockMvc.perform(post("/api/courts")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content(courtBodyA))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/courts")
                        .header("X-GP-Token", VALID_TOKEN).contentType(MediaType.APPLICATION_JSON).content(courtBodyB))
                .andExpect(status().isCreated());
    }

    @Test
    void courtDeleteAndVenueDeleteRoundTrip() throws Exception {
        String venueId = createVenue("Venue for delete test " + NAME_COUNTER.incrementAndGet());
        String courtBody = objectMapper.writeValueAsString(new CourtController.CreateCourtRequest(venueId, "Bana X", null, null));
        String response = mockMvc.perform(post("/api/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(courtBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String courtId = objectMapper.readTree(response).get("id").asText();

        // A venue with a court still referencing it can't be deleted (FK RESTRICT -> 409).
        mockMvc.perform(delete("/api/venues/" + venueId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/courts/" + courtId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/courts/" + courtId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/venues/" + venueId).header("X-GP-Token", VALID_TOKEN))
                .andExpect(status().isNoContent());
    }

    @Test
    void createCourtUnderUnknownVenueReturns404() throws Exception {
        String courtBody = objectMapper.writeValueAsString(
                new CourtController.CreateCourtRequest("does-not-exist", "Bana Z", null, null));
        mockMvc.perform(post("/api/courts")
                        .header("X-GP-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(courtBody))
                .andExpect(status().isNotFound());
    }
}
