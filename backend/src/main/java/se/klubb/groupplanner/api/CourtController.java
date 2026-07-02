package se.klubb.groupplanner.api;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.Court;
import se.klubb.groupplanner.repo.CourtRepository;
import se.klubb.groupplanner.repo.VenueRepository;
import se.klubb.groupplanner.resources.DefaultVenueService;
import se.klubb.groupplanner.util.Uuid7;

/**
 * CRUD for {@code Court} (spec §6.8/§7.8), club-level — {@code /api/courts}. {@code POST} without a
 * {@code venueId} auto-creates the default venue ("Hallen") on first use (spec §12.2/docs/plan.md
 * M5 row) via {@link DefaultVenueService} — the same fallback the block-generation endpoint uses for
 * its own auto-created "Bana N" courts, so a council member who never touches Venue CRUD directly
 * still ends up with one sensible venue.
 */
@RestController
@RequestMapping("/api/courts")
public class CourtController {

    private final CourtRepository courtRepository;
    private final VenueRepository venueRepository;
    private final DefaultVenueService defaultVenueService;

    public CourtController(CourtRepository courtRepository, VenueRepository venueRepository, DefaultVenueService defaultVenueService) {
        this.courtRepository = courtRepository;
        this.venueRepository = venueRepository;
        this.defaultVenueService = defaultVenueService;
    }

    @GetMapping
    public List<Court> list(@RequestParam(required = false) String venueId) {
        if (venueId != null) {
            requireVenueExists(venueId);
            return courtRepository.findByVenueId(venueId);
        }
        return courtRepository.findAll();
    }

    @GetMapping("/{id}")
    public Court get(@PathVariable String id) {
        return findOrThrow(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Court create(@RequestBody CreateCourtRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        String venueId = request.venueId();
        if (venueId == null || venueId.isBlank()) {
            venueId = defaultVenueService.resolveOrCreateDefaultVenueId();
        } else {
            requireVenueExists(venueId);
        }
        boolean active = request.active() == null || request.active();
        Court court = new Court(Uuid7.generate(), venueId, request.name(), active, request.notes());
        return courtRepository.insert(court);
    }

    @PatchMapping("/{id}")
    public Court update(@PathVariable String id, @RequestBody UpdateCourtRequest request) {
        Court existing = findOrThrow(id);
        if (request == null) {
            return existing;
        }
        Court updated = new Court(
                existing.id(),
                existing.venueId(),
                request.name() != null ? request.name() : existing.name(),
                request.active() != null ? request.active() : existing.active(),
                request.notes() != null ? request.notes() : existing.notes());
        return courtRepository.update(updated);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        findOrThrow(id);
        courtRepository.deleteById(id);
    }

    private void requireVenueExists(String venueId) {
        if (venueRepository.findById(venueId).isEmpty()) {
            throw new NotFoundException("Venue not found: " + venueId);
        }
    }

    private Court findOrThrow(String id) {
        return courtRepository.findById(id).orElseThrow(() -> new NotFoundException("Court not found: " + id));
    }

    public record CreateCourtRequest(String venueId, String name, Boolean active, String notes) {
    }

    public record UpdateCourtRequest(String name, Boolean active, String notes) {
    }
}
