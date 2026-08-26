import { afterEach, describe, expect, it, vi } from "vitest";
import { UI_MODE_STORAGE_KEY } from "./uiMode";
import { readUiModeFromStorage, writeUiModeToStorage } from "./uiModeStorage";

describe("uiModeStorage", () => {
  afterEach(() => {
    window.localStorage.removeItem(UI_MODE_STORAGE_KEY);
    vi.restoreAllMocks();
  });

  it("readUiModeFromStorage returns null when nothing is stored", () => {
    expect(readUiModeFromStorage()).toBeNull();
  });

  it("writeUiModeToStorage then readUiModeFromStorage round-trips", () => {
    writeUiModeToStorage("ADVANCED");
    expect(readUiModeFromStorage()).toBe("ADVANCED");
    writeUiModeToStorage("SIMPLE");
    expect(readUiModeFromStorage()).toBe("SIMPLE");
  });

  it("readUiModeFromStorage ignores a corrupt/unrecognized stored value", () => {
    window.localStorage.setItem(UI_MODE_STORAGE_KEY, "not-a-mode");
    expect(readUiModeFromStorage()).toBeNull();
  });

  it("readUiModeFromStorage fails safe (returns null) if localStorage.getItem throws", () => {
    vi.spyOn(window.localStorage, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(readUiModeFromStorage()).toBeNull();
  });

  it("writeUiModeToStorage fails safe (does not throw) if localStorage.setItem throws", () => {
    vi.spyOn(window.localStorage, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(() => writeUiModeToStorage("ADVANCED")).not.toThrow();
  });
});
