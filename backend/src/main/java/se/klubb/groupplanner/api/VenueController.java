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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.Court;
import se.klubb.groupplanner.domain.Venue;
import se.klubb.groupplanner.repo.CourtRepository;
import se.klubb.groupplanner.repo.VenueRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * CRUD for {@code Venue} (spec §7.7), club-level (not scoped to any season/activity plan) —
 * {@code /api/venues}, with each venue's courts nested in the response (docs/plan.md M5 row).
 */
@RestController
@RequestMapping("/api/venues")
public class VenueController {

    private final VenueRepository venueRepository;
    private final CourtRepository courtRepository;

    public VenueController(VenueRepository venueRepository, CourtRepository courtRepository) {
        this.venueRepository = venueRepository;
        this.courtRepository = courtRepository;
    }

    @GetMapping
    public List<VenueView> list() {
        return venueRepository.findAll().stream().map(this::toView).toList();
    }

    @GetMapping("/{id}")
    public VenueView get(@PathVariable String id) {
        return toView(findOrThrow(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VenueView create(@RequestBody CreateVenueRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        Venue venue = venueRepository.insert(new Venue(Uuid7.generate(), request.name(), request.notes()));
        return toView(venue);
    }

    @PatchMapping("/{id}")
    public VenueView update(@PathVariable String id, @RequestBody UpdateVenueRequest request) {
        Venue existing = findOrThrow(id);
        if (request == null) {
            return toView(existing);
        }
        Venue updated = new Venue(
                existing.id(),
                request.name() != null ? request.name() : existing.name(),
                request.notes() != null ? request.notes() : existing.notes());
        return toView(venueRepository.update(updated));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        findOrThrow(id);
        venueRepository.deleteById(id);
    }

    private VenueView toView(Venue venue) {
        return new VenueView(venue.id(), venue.name(), venue.notes(), courtRepository.findByVenueId(venue.id()));
    }

    private Venue findOrThrow(String id) {
        return venueRepository.findById(id).orElseThrow(() -> new NotFoundException("Venue not found: " + id));
    }

    public record CreateVenueRequest(String name, String notes) {
    }

    public record UpdateVenueRequest(String name, String notes) {
    }

    public record VenueView(String id, String name, String notes, List<Court> courts) {
    }
}
