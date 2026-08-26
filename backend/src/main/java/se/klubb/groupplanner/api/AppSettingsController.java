package se.klubb.groupplanner.api;

import java.util.Locale;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.repo.AppSettingsRepository;

/**
 * App-wide UI settings (v0.6.0 B3): {@code GET|PUT /api/app-settings}, currently just {@code
 * uiMode} (SIMPLE|ADVANCED), persisted as a single global {@code app_setting} row (V12) — this
 * desktop app has no per-user model beyond the shared {@code X-GP-Token}, so one club-wide setting
 * is enough.
 */
@RestController
public class AppSettingsController {

    private static final String UI_MODE_KEY = "ui.mode";
    private static final String DEFAULT_UI_MODE = "SIMPLE";
    private static final Set<String> VALID_UI_MODES = Set.of("SIMPLE", "ADVANCED");

    private final AppSettingsRepository appSettingsRepository;

    public AppSettingsController(AppSettingsRepository appSettingsRepository) {
        this.appSettingsRepository = appSettingsRepository;
    }

    @GetMapping("/api/app-settings")
    public AppSettingsResponse get() {
        String uiMode = appSettingsRepository.findValue(UI_MODE_KEY).orElse(DEFAULT_UI_MODE);
        return new AppSettingsResponse(uiMode);
    }

    /**
     * {@code @RequestBody(required = false)}: a completely missing/empty body (or a literal JSON
     * {@code null}) would otherwise make Spring raise {@code HttpMessageNotReadableException}
     * ("Malformed request body") before this method even runs, instead of the Swedish 400 below -
     * {@code required = false} lets {@code request} come through as {@code null} so the same
     * validation path handles it. {@code uiMode} is trimmed + uppercased before validation (mirrors
     * {@code TimeSlotController.normalizeDayOfWeek}), so {@code "simple"}/{@code " ADVANCED "} are
     * accepted; a null/blank/unrecognized value after normalization is the same Swedish 400 -
     * {@code Set.of(...).contains(null)} would NPE, so the null check comes first.
     */
    @PutMapping("/api/app-settings")
    public AppSettingsResponse update(@RequestBody(required = false) UpdateAppSettingsRequest request) {
        String uiMode = normalizeUiMode(request == null ? null : request.uiMode());
        if (uiMode == null || !VALID_UI_MODES.contains(uiMode)) {
            throw new BadRequestException("Ogiltigt läge – tillåtna värden är SIMPLE och ADVANCED.");
        }
        appSettingsRepository.upsert(UI_MODE_KEY, uiMode);
        return new AppSettingsResponse(uiMode);
    }

    private static String normalizeUiMode(String uiMode) {
        if (uiMode == null || uiMode.isBlank()) {
            return null;
        }
        return uiMode.trim().toUpperCase(Locale.ROOT);
    }

    public record AppSettingsResponse(String uiMode) {
    }

    public record UpdateAppSettingsRequest(String uiMode) {
    }
}
