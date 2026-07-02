package se.klubb.groupplanner.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.demo.DemoDataService;

/**
 * v0.3.0 user feedback ("Ha demo-data för beachvolley så att man kan dema det utan att importera en
 * excelfil.") — one click creates a complete, fictional, ready-to-solve season+plan so the council
 * can demo the app without an xlsx file. Delegates entirely to {@link DemoDataService}, which writes
 * through the exact repositories/services the import wizard and REST controllers use.
 *
 * <p>Deliberately takes no request body: the demo dataset is fully self-contained and deterministic
 * (see {@link DemoDataService}'s javadoc). Safe to call repeatedly — every call creates a fresh
 * season (numbered if a demo season already exists), so it can never fail because a demo was loaded
 * before.
 */
@RestController
public class DemoDataController {

    private final DemoDataService demoDataService;

    public DemoDataController(DemoDataService demoDataService) {
        this.demoDataService = demoDataService;
    }

    @PostMapping("/api/demo")
    @ResponseStatus(HttpStatus.CREATED)
    public DemoDataService.DemoResult create() {
        return demoDataService.createDemoSeason();
    }
}
