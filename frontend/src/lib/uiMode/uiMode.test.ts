import { afterEach, describe, expect, it } from "vitest";
import { DEFAULT_UI_MODE, UI_MODE_STORAGE_KEY, hasUiModeQueryOverride, resolveInitialUiMode } from "./uiMode";

describe("resolveInitialUiMode", () => {
  afterEach(() => {
    window.localStorage.removeItem(UI_MODE_STORAGE_KEY);
  });

  it("falls back to the built-in default when no URL param and no stored value exist", () => {
    expect(resolveInitialUiMode("")).toBe(DEFAULT_UI_MODE);
    expect(resolveInitialUiMode()).toBe(DEFAULT_UI_MODE);
  });

  it("reads a valid localStorage mirror when no URL param is present", () => {
    window.localStorage.setItem(UI_MODE_STORAGE_KEY, "ADVANCED");
    expect(resolveInitialUiMode("")).toBe("ADVANCED");
  });

  it("URL param beats localStorage", () => {
    window.localStorage.setItem(UI_MODE_STORAGE_KEY, "ADVANCED");
    expect(resolveInitialUiMode("?lage=enkelt")).toBe("SIMPLE");
  });

  it("maps both recognized URL param values", () => {
    expect(resolveInitialUiMode("?lage=enkelt")).toBe("SIMPLE");
    expect(resolveInitialUiMode("?lage=avancerat")).toBe("ADVANCED");
  });

  it("an invalid URL param value falls through to localStorage", () => {
    window.localStorage.setItem(UI_MODE_STORAGE_KEY, "ADVANCED");
    expect(resolveInitialUiMode("?lage=bogus")).toBe("ADVANCED");
  });

  it("an invalid URL param value falls through to the default when nothing is stored", () => {
    expect(resolveInitialUiMode("?lage=bogus")).toBe(DEFAULT_UI_MODE);
  });

  it("a corrupt/invalid stored value is ignored, falling back to the default", () => {
    window.localStorage.setItem(UI_MODE_STORAGE_KEY, "not-a-mode");
    expect(resolveInitialUiMode("")).toBe(DEFAULT_UI_MODE);
  });

  it("an unrelated query param is ignored", () => {
    window.localStorage.setItem(UI_MODE_STORAGE_KEY, "ADVANCED");
    expect(resolveInitialUiMode("?foo=bar")).toBe("ADVANCED");
  });

  // B1: a `?lage=` value equal to an inherited Object.prototype property name must be ignored, not
  // treated as a recognized override via `in` matching against the prototype chain - and the
  // backend reconcile (UiModeSync) must not be blocked by such a value either.
  it.each(["toString", "constructor", "__proto__"])(
    "a %s query param value is ignored, falling through to localStorage/default rather than matching Object.prototype",
    (value) => {
      window.localStorage.setItem(UI_MODE_STORAGE_KEY, "ADVANCED");
      expect(resolveInitialUiMode(`?lage=${value}`)).toBe("ADVANCED");
      window.localStorage.removeItem(UI_MODE_STORAGE_KEY);
      expect(resolveInitialUiMode(`?lage=${value}`)).toBe(DEFAULT_UI_MODE);
    },
  );
});

describe("hasUiModeQueryOverride", () => {
  it("is true only for a recognized ?lage= value", () => {
    expect(hasUiModeQueryOverride("?lage=enkelt")).toBe(true);
    expect(hasUiModeQueryOverride("?lage=avancerat")).toBe(true);
    expect(hasUiModeQueryOverride("?lage=bogus")).toBe(false);
    expect(hasUiModeQueryOverride("")).toBe(false);
    expect(hasUiModeQueryOverride("?foo=bar")).toBe(false);
  });

  it.each(["toString", "constructor", "__proto__"])(
    "is false for the Object.prototype property name %s (not a prototype-pollution match)",
    (value) => {
      expect(hasUiModeQueryOverride(`?lage=${value}`)).toBe(false);
    },
  );
});
