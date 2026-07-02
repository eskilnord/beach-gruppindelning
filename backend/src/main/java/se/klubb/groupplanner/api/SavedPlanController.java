package se.klubb.groupplanner.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.domain.SavedPlan;
import se.klubb.groupplanner.savedplan.SavedPlanDetailView;
import se.klubb.groupplanner.savedplan.SavedPlanService;

/**
 * The "spara plan" lifecycle REST surface (spec §14.1-§14.4, M8 task item 1): {@code POST} snapshots
 * the plan's CURRENT state; {@code GET} list/one; {@code PATCH} walks the status flow ({@code
 * draft -> saved -> locked -> published -> archived}); {@code DELETE} removes a still-mutable
 * ({@code draft}/{@code saved}) snapshot. See {@link SavedPlanService} for the full behavior
 * (snapshot content, resource-usage materialization, transition legality).
 */
@RestController
public class SavedPlanController {

    private final SavedPlanService savedPlanService;

    public SavedPlanController(SavedPlanService savedPlanService) {
        this.savedPlanService = savedPlanService;
    }

    @PostMapping("/api/plans/{planId}/saved-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public SavedPlanDetailView save(@PathVariable String planId, @RequestBody(required = false) SaveRequest request) {
        String name = request == null ? null : request.name();
        return savedPlanService.save(planId, name);
    }

    @GetMapping("/api/plans/{planId}/saved-plans")
    public List<SavedPlan> list(@PathVariable String planId) {
        return savedPlanService.list(planId);
    }

    @GetMapping("/api/plans/{planId}/saved-plans/{savedPlanId}")
    public SavedPlanDetailView getOne(@PathVariable String planId, @PathVariable String savedPlanId) {
        return savedPlanService.findOne(planId, savedPlanId);
    }

    @PatchMapping("/api/plans/{planId}/saved-plans/{savedPlanId}")
    public SavedPlanDetailView updateStatus(
            @PathVariable String planId, @PathVariable String savedPlanId, @RequestBody(required = false) StatusRequest request) {
        String status = request == null ? null : request.status();
        return savedPlanService.transitionStatus(planId, savedPlanId, status);
    }

    @DeleteMapping("/api/plans/{planId}/saved-plans/{savedPlanId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String planId, @PathVariable String savedPlanId) {
        savedPlanService.delete(planId, savedPlanId);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SaveRequest(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatusRequest(String status) {
    }
}
