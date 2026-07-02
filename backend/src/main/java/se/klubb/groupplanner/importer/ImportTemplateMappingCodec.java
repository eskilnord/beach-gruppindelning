package se.klubb.groupplanner.importer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import se.klubb.groupplanner.api.error.BadRequestException;

/**
 * (De)serializes an {@code import_template.mapping_json} value: a JSON object of {@code
 * {"<columnIndex>": "<target>"}} (see {@link ColumnMapping#targetString()} for the target grammar).
 * Kept as one small codec shared by {@code ImportCommitService} (writes it when saving a template)
 * and {@code ImportController} (reads it back to suggest a matched template's mapping on {@code
 * GET .../columns}).
 */
public final class ImportTemplateMappingCodec {

    private static final TypeReference<Map<Integer, String>> MAP_TYPE = new TypeReference<>() {
    };

    private ImportTemplateMappingCodec() {
    }

    public static String encode(ObjectMapper objectMapper, List<ColumnMapping> mappings) {
        Map<Integer, String> byColumn = new TreeMap<>();
        for (ColumnMapping mapping : mappings) {
            byColumn.put(mapping.columnIndex(), mapping.targetString());
        }
        try {
            return objectMapper.writeValueAsString(byColumn);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not encode mapping_json", e);
        }
    }

    public static Map<Integer, String> decode(ObjectMapper objectMapper, String mappingJson) {
        try {
            return objectMapper.readValue(mappingJson, MAP_TYPE);
        } catch (Exception e) {
            throw new BadRequestException("Corrupt import_template.mapping_json: " + e.getMessage());
        }
    }
}
