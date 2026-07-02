import { describe, expect, it } from "vitest";
import { generateFieldKey } from "./fieldKey";

describe("generateFieldKey", () => {
  it("camelCases a simple multi-word label", () => {
    expect(generateFieldKey("Vill spela med")).toBe("villSpelaMed");
  });

  it("transliterates å/ä/ö to a/a/o", () => {
    expect(generateFieldKey("Är ny i klubben")).toBe("arNyIKlubben");
    expect(generateFieldKey("Önskar tränare")).toBe("onskarTranare");
    expect(generateFieldKey("Låg nivå")).toBe("lagNiva");
  });

  it("drops punctuation and collapses whitespace", () => {
    expect(generateFieldKey("Kan inte tider!")).toBe("kanInteTider");
    expect(generateFieldKey("  Vill   spela med  ")).toBe("villSpelaMed");
  });

  it("prefixes a leading digit so the key still starts with a letter", () => {
    expect(generateFieldKey("2026 säsong")).toBe("field2026Sasong");
  });

  it("falls back to a safe default for a label with no alphanumeric characters", () => {
    expect(generateFieldKey("???")).toBe("field");
  });

  it("matches the backend KEY_PATTERN (^[a-zA-Z][a-zA-Z0-9]*$) for every case above", () => {
    const pattern = /^[a-zA-Z][a-zA-Z0-9]*$/;
    for (const label of ["Vill spela med", "Är ny i klubben", "Kan inte tider!", "2026 säsong", "???"]) {
      expect(generateFieldKey(label)).toMatch(pattern);
    }
  });
});
