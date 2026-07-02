package se.klubb.groupplanner.importer.parse;

import java.util.List;
import java.util.Optional;

/** A fully-parsed source file (xlsx: one entry per worksheet; CSV: exactly one entry). */
public record ParsedWorkbook(List<ParsedSheet> sheets) {

    public Optional<ParsedSheet> sheetByName(String name) {
        return sheets.stream().filter(s -> s.name().equals(name)).findFirst();
    }
}
