package se.klubb.groupplanner.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * CRUD for {@code SeasonPlan} (spec §7.5), the top-level season container (docs/design/02
 * -product-data-ui.md §8 REST surface: {@code /api/seasons}).
 */
@RestController
@RequestMapping("/api/seasons")
public class SeasonPlanController {

    private static final String DEFAULT_STATUS = "active";

    private final SeasonPlanRepository repository;

    public SeasonPlanController(SeasonPlanRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<SeasonPlan> list() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public SeasonPlan get(@PathVariable String id) {
        return findOrThrow(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SeasonPlan create(@RequestBody CreateSeasonPlanRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        Instant now = Instant.now();
        SeasonPlan seasonPlan = new SeasonPlan(
                Uuid7.generate(),
                request.name(),
                request.startDate(),
                request.endDate(),
                request.status() == null || request.status().isBlank() ? DEFAULT_STATUS : request.status(),
                now,
                now);
        return repository.insert(seasonPlan);
    }

    @PatchMapping("/{id}")
    public SeasonPlan update(@PathVariable String id, @RequestBody UpdateSeasonPlanRequest request) {
        SeasonPlan existing = findOrThrow(id);
        if (request == null) {
            return existing;
        }
        SeasonPlan updated = new SeasonPlan(
                existing.id(),
                request.name() != null ? request.name() : existing.name(),
                request.startDate() != null ? request.startDate() : existing.startDate(),
                request.endDate() != null ? request.endDate() : existing.endDate(),
                request.status() != null ? request.status() : existing.status(),
                existing.createdAt(),
                Instant.now());
        return repository.update(updated);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        findOrThrow(id);
        repository.deleteById(id);
    }

    private SeasonPlan findOrThrow(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Season plan not found: " + id));
    }

    public record CreateSeasonPlanRequest(String name, LocalDate startDate, LocalDate endDate, String status) {
    }

    public record UpdateSeasonPlanRequest(String name, LocalDate startDate, LocalDate endDate, String status) {
    }
}
