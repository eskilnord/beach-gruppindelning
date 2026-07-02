package se.klubb.groupplanner.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.ImportTemplate;
import se.klubb.groupplanner.importer.ColumnMapping;
import se.klubb.groupplanner.importer.ColumnMappingSuggester;
import se.klubb.groupplanner.importer.CommitOptions;
import se.klubb.groupplanner.importer.CommitResult;
import se.klubb.groupplanner.importer.ImportCommitService;
import se.klubb.groupplanner.importer.ImportSession;
import se.klubb.groupplanner.importer.ImportSessionService;
import se.klubb.groupplanner.importer.ImportTemplateMappingCodec;
import se.klubb.groupplanner.importer.ImportValidationService;
import se.klubb.groupplanner.importer.MappingTargetKind;
import se.klubb.groupplanner.importer.RowDecision;
import se.klubb.groupplanner.importer.RowValidationResult;
import se.klubb.groupplanner.importer.parse.ParsedSheet;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ImportTemplateRepository;

/**
 * The generic import wizard REST surface (spec §8, docs/design/02-product-data-ui.md §2), mounted
 * under {@code /api/plans/{planId}/import} per the M3 milestone brief. Every endpoint is
 * token-guarded like the rest of the API ({@code TokenAuthFilter} on {@code /api/*}).
 *
 * <p>See backend/docs/m3-notes.md for the full endpoint-by-endpoint rationale, especially how
 * "the sheet" is selected (via {@code PUT .../header}) for the endpoints that don't take an
 * explicit {@code sheet} parameter.
 */
@RestController
public class ImportController {

    private static final int DEFAULT_PREVIEW_ROWS = 30;
    private static final int SAMPLE_VALUES_PER_COLUMN = 5;

    private final ImportSessionService importSessionService;
    private final ImportValidationService importValidationService;
    private final ImportCommitService importCommitService;
    private final ActivityPlanRepository activityPlanRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final ImportTemplateRepository importTemplateRepository;
    private final ObjectMapper objectMapper;

    public ImportController(
            ImportSessionService importSessionService,
            ImportValidationService importValidationService,
            ImportCommitService importCommitService,
            ActivityPlanRepository activityPlanRepository,
            FieldDefinitionRepository fieldDefinitionRepository,
            ImportTemplateRepository importTemplateRepository,
            ObjectMapper objectMapper) {
        this.importSessionService = importSessionService;
        this.importValidationService = importValidationService;
        this.importCommitService = importCommitService;
        this.activityPlanRepository = activityPlanRepository;
        this.fieldDefinitionRepository = fieldDefinitionRepository;
        this.importTemplateRepository = importTemplateRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/plans/{planId}/import/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportSessionService.CreatedSession createSession(
            @PathVariable String planId, @RequestPart("file") MultipartFile file) {
        requirePlanExists(planId);
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("file is required and must not be empty");
        }
        try {
            return importSessionService.createSession(planId, file.getOriginalFilename(), file.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read uploaded file", e);
        }
    }

    @GetMapping("/api/plans/{planId}/import/sessions/{sid}/preview")
    public PreviewResponse preview(
            @PathVariable String planId,
            @PathVariable String sid,
            @RequestParam String sheet,
            @RequestParam(defaultValue = "" + DEFAULT_PREVIEW_ROWS) int rows) {
        requirePlanExists(planId);
        ImportSession session = importSessionService.getForPlan(sid, planId);
        ParsedSheet parsedSheet = session.sheetOrThrow(sheet);
        int headerRowIndex = session.headerRowIndex(sheet);

        int rowLimit = Math.min(rows, parsedSheet.rowCount());
        List<List<String>> grid = new ArrayList<>(rowLimit);
        for (int r = 0; r < rowLimit; r++) {
            List<String> rowValues = new ArrayList<>(parsedSheet.columnCount());
            for (int c = 0; c < parsedSheet.columnCount(); c++) {
                rowValues.add(parsedSheet.cellAt(r, c).rawString());
            }
            grid.add(rowValues);
        }
        return new PreviewResponse(sheet, headerRowIndex, parsedSheet.rowCount(), grid);
    }

    @PutMapping("/api/plans/{planId}/import/sessions/{sid}/header")
    public HeaderResponse setHeader(
            @PathVariable String planId, @PathVariable String sid, @RequestBody HeaderRequest request) {
        requirePlanExists(planId);
        ImportSession session = importSessionService.getForPlan(sid, planId);
        if (request == null || request.sheet() == null || request.sheet().isBlank()) {
            throw new BadRequestException("sheet is required");
        }
        if (request.headerRowIndex() == null || request.headerRowIndex() < 0) {
            throw new BadRequestException("headerRowIndex must be >= 0");
        }
        session.setHeaderRow(request.sheet(), request.headerRowIndex());
        return new HeaderResponse(request.sheet(), request.headerRowIndex());
    }

    @GetMapping("/api/plans/{planId}/import/sessions/{sid}/columns")
    public ColumnsResponse columns(@PathVariable String planId, @PathVariable String sid) {
        requirePlanExists(planId);
        ImportSession session = importSessionService.getForPlan(sid, planId);
        String sheetName = session.selectedSheetOrThrow();
        ParsedSheet sheet = session.sheetOrThrow(sheetName);
        int headerRowIndex = session.headerRowIndex(sheetName);

        Map<Integer, String> templateMapping = session.templateMatch(sheetName)
                .flatMap(match -> importTemplateRepository.findById(match.templateId()))
                .map(template -> ImportTemplateMappingCodec.decode(objectMapper, template.mappingJson()))
                .orElse(Map.of());

        List<ColumnInfo> columns = new ArrayList<>(sheet.columnCount());
        for (int col = 0; col < sheet.columnCount(); col++) {
            String headerText = sheet.cellAt(headerRowIndex, col).rawString();
            List<String> samples = sampleValues(sheet, headerRowIndex, col);
            String suggested = templateMapping.get(col);
            if (suggested == null) {
                suggested = ColumnMappingSuggester.suggest(headerText).map(MappingTargetKind::wireName).orElse(null);
            }
            columns.add(new ColumnInfo(col, headerText, samples, suggested));
        }
        return new ColumnsResponse(sheetName, headerRowIndex, columns);
    }

    @PutMapping("/api/plans/{planId}/import/sessions/{sid}/mapping")
    public MappingResponse setMapping(
            @PathVariable String planId, @PathVariable String sid, @RequestBody MappingRequest request) {
        requirePlanExists(planId);
        ImportSession session = importSessionService.getForPlan(sid, planId);
        if (request == null || request.sheet() == null || request.sheet().isBlank()) {
            throw new BadRequestException("sheet is required");
        }
        if (request.mappings() == null) {
            throw new BadRequestException("mappings is required");
        }

        List<ColumnMapping> mappings = new ArrayList<>(request.mappings().size());
        java.util.Set<Integer> seenColumns = new java.util.HashSet<>();
        for (ColumnMappingDto dto : request.mappings()) {
            if (!seenColumns.add(dto.columnIndex())) {
                throw new BadRequestException("Duplicate mapping for column " + dto.columnIndex());
            }
            ColumnMapping mapping = ColumnMapping.fromTargetString(dto.columnIndex(), dto.target());
            if (mapping.kind() == MappingTargetKind.CUSTOM_FIELD) {
                var field = fieldDefinitionRepository.findByKeyVisibleToPlan(planId, mapping.customFieldKey())
                        .orElseThrow(() -> new BadRequestException("Unknown custom field key: " + mapping.customFieldKey()));
                if (!"CUSTOM".equals(field.storageKind())) {
                    throw new BadRequestException(
                            "'" + mapping.customFieldKey() + "' is a structured field - map its column to the "
                                    + "matching top-level target instead of customField:" + mapping.customFieldKey());
                }
            }
            mappings.add(mapping);
        }

        session.setMappings(request.sheet(), mappings);
        return new MappingResponse(request.sheet(), request.mappings());
    }

    @GetMapping("/api/plans/{planId}/import/sessions/{sid}/validate")
    public ValidateResponse validate(@PathVariable String planId, @PathVariable String sid) {
        requirePlanExists(planId);
        ImportSession session = importSessionService.getForPlan(sid, planId);
        List<RowValidationResult> results = importValidationService.validate(session, planId);

        long ok = results.stream().filter(r -> r.status() == se.klubb.groupplanner.importer.RowStatus.OK).count();
        long warn = results.stream().filter(r -> r.status() == se.klubb.groupplanner.importer.RowStatus.WARN).count();
        long skip = results.stream().filter(r -> r.status() == se.klubb.groupplanner.importer.RowStatus.SKIP).count();
        return new ValidateResponse(results, results.size(), (int) ok, (int) warn, (int) skip);
    }

    @PutMapping("/api/plans/{planId}/import/sessions/{sid}/decisions")
    public Map<String, DecisionDto> setDecisions(
            @PathVariable String planId, @PathVariable String sid, @RequestBody Map<String, DecisionDto> decisions) {
        requirePlanExists(planId);
        ImportSession session = importSessionService.getForPlan(sid, planId);
        if (decisions == null) {
            throw new BadRequestException("Request body (rowIndex -> decision map) is required");
        }
        Map<String, DecisionDto> applied = new LinkedHashMap<>();
        for (Map.Entry<String, DecisionDto> entry : decisions.entrySet()) {
            int rowIndex = parseRowIndex(entry.getKey());
            DecisionDto dto = entry.getValue();
            if (dto == null || dto.action() == null) {
                throw new BadRequestException("Missing action for row " + rowIndex);
            }
            RowDecision decision = switch (dto.action()) {
                case "CREATE_NEW" -> RowDecision.createNew();
                case "SKIP" -> RowDecision.skip();
                case "MATCH_EXISTING" -> RowDecision.matchExisting(dto.personId());
                default -> throw new BadRequestException("Unknown decision action: " + dto.action());
            };
            session.setDecision(rowIndex, decision);
            applied.put(entry.getKey(), dto);
        }
        return applied;
    }

    @PostMapping("/api/plans/{planId}/import/sessions/{sid}/commit")
    public CommitResult commit(
            @PathVariable String planId, @PathVariable String sid, @RequestBody(required = false) CommitRequest request) {
        requirePlanExists(planId);
        ImportSession session = importSessionService.getForPlan(sid, planId);
        CommitOptions options = request == null
                ? CommitOptions.none()
                : new CommitOptions(request.saveAsTemplate() != null && request.saveAsTemplate(), request.templateName());
        CommitResult result = importCommitService.commit(session, planId, options);
        importSessionService.delete(sid);
        return result;
    }

    @DeleteMapping("/api/plans/{planId}/import/sessions/{sid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable String planId, @PathVariable String sid) {
        requirePlanExists(planId);
        importSessionService.getForPlan(sid, planId); // 404 if unknown/expired, 400 if another plan's.
        importSessionService.delete(sid);
    }

    @GetMapping("/api/import/templates")
    public List<ImportTemplate> listTemplates() {
        return importTemplateRepository.findAll();
    }

    private void requirePlanExists(String planId) {
        if (activityPlanRepository.findById(planId).isEmpty()) {
            throw new NotFoundException("Activity plan not found: " + planId);
        }
    }

    private static List<String> sampleValues(ParsedSheet sheet, int headerRowIndex, int columnIndex) {
        List<String> samples = new ArrayList<>(SAMPLE_VALUES_PER_COLUMN);
        for (int r = headerRowIndex + 1; r < sheet.rowCount() && samples.size() < SAMPLE_VALUES_PER_COLUMN; r++) {
            var cell = sheet.cellAt(r, columnIndex);
            if (!cell.isBlank()) {
                samples.add(cell.rawString());
            }
        }
        return samples;
    }

    private static int parseRowIndex(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Invalid row index key: " + key);
        }
    }

    public record HeaderRequest(String sheet, Integer headerRowIndex) {
    }

    public record HeaderResponse(String sheet, int headerRowIndex) {
    }

    public record PreviewResponse(String sheet, int headerRowIndex, int rowCount, List<List<String>> rows) {
    }

    public record ColumnInfo(int columnIndex, String headerText, List<String> sampleValues, String suggestedTarget) {
    }

    public record ColumnsResponse(String sheet, int headerRowIndex, List<ColumnInfo> columns) {
    }

    public record ColumnMappingDto(int columnIndex, String target) {
    }

    public record MappingRequest(String sheet, List<ColumnMappingDto> mappings) {
    }

    public record MappingResponse(String sheet, List<ColumnMappingDto> mappings) {
    }

    public record ValidateResponse(List<RowValidationResult> rows, int totalRows, int okCount, int warnCount, int skipCount) {
    }

    public record DecisionDto(String action, String personId) {
    }

    public record CommitRequest(Boolean saveAsTemplate, String templateName) {
    }
}
