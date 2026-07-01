package se.klubb.groupplanner.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.domain.ConstraintDefinition;
import se.klubb.groupplanner.repo.ConstraintDefinitionRepository;

/**
 * Read-only listing of the 24 standard constraints (spec §10), seeded by
 * {@code V2__seed_constraints_and_standard_fields.sql} — docs/design/02-product-data-ui.md §8:
 * {@code GET /api/constraint-definitions}.
 */
@RestController
@RequestMapping("/api/constraint-definitions")
public class ConstraintDefinitionController {

    private final ConstraintDefinitionRepository repository;

    public ConstraintDefinitionController(ConstraintDefinitionRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<ConstraintDefinition> list() {
        return repository.findAll();
    }
}
