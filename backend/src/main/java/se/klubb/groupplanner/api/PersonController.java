package se.klubb.groupplanner.api;

import java.time.Instant;
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
import se.klubb.groupplanner.domain.Person;
import se.klubb.groupplanner.repo.PersonRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * CRUD for {@code Person} (spec §7.1) — docs/design/02-product-data-ui.md §8: {@code /api/persons}.
 */
@RestController
@RequestMapping("/api/persons")
public class PersonController {

    private final PersonRepository repository;

    public PersonController(PersonRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Person> list() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public Person get(@PathVariable String id) {
        return findOrThrow(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Person create(@RequestBody CreatePersonRequest request) {
        if (request == null || request.firstName() == null || request.firstName().isBlank()
                || request.lastName() == null || request.lastName().isBlank()) {
            throw new BadRequestException("firstName and lastName are required");
        }
        Instant now = Instant.now();
        Person person = new Person(
                Uuid7.generate(),
                request.firstName(),
                request.lastName(),
                request.displayName(),
                request.email(),
                request.phone(),
                request.externalId(),
                request.canBeParticipant() == null || request.canBeParticipant(),
                request.canBeCoach() != null && request.canBeCoach(),
                request.notes(),
                now,
                now);
        return repository.insert(person);
    }

    @PatchMapping("/{id}")
    public Person update(@PathVariable String id, @RequestBody UpdatePersonRequest request) {
        Person existing = findOrThrow(id);
        if (request == null) {
            return existing;
        }
        Person updated = new Person(
                existing.id(),
                request.firstName() != null ? request.firstName() : existing.firstName(),
                request.lastName() != null ? request.lastName() : existing.lastName(),
                request.displayName() != null ? request.displayName() : existing.displayName(),
                request.email() != null ? request.email() : existing.email(),
                request.phone() != null ? request.phone() : existing.phone(),
                request.externalId() != null ? request.externalId() : existing.externalId(),
                request.canBeParticipant() != null ? request.canBeParticipant() : existing.canBeParticipant(),
                request.canBeCoach() != null ? request.canBeCoach() : existing.canBeCoach(),
                request.notes() != null ? request.notes() : existing.notes(),
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

    private Person findOrThrow(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Person not found: " + id));
    }

    public record CreatePersonRequest(
            String firstName,
            String lastName,
            String displayName,
            String email,
            String phone,
            String externalId,
            Boolean canBeParticipant,
            Boolean canBeCoach,
            String notes) {
    }

    public record UpdatePersonRequest(
            String firstName,
            String lastName,
            String displayName,
            String email,
            String phone,
            String externalId,
            Boolean canBeParticipant,
            Boolean canBeCoach,
            String notes) {
    }
}
