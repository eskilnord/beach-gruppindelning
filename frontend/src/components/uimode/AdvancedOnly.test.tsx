import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "../../test/renderWithProviders";
import { AdvancedOnly, SimpleOnly } from "./AdvancedOnly";

describe("AdvancedOnly", () => {
  it("renders children in ADVANCED mode", () => {
    renderWithProviders(<AdvancedOnly>only-advanced-content</AdvancedOnly>, { uiMode: "ADVANCED" });
    expect(screen.getByText("only-advanced-content")).toBeInTheDocument();
  });

  it("renders nothing (no fallback given) in SIMPLE mode", () => {
    renderWithProviders(<AdvancedOnly>only-advanced-content</AdvancedOnly>, { uiMode: "SIMPLE" });
    expect(screen.queryByText("only-advanced-content")).not.toBeInTheDocument();
  });

  it("renders the given fallback in SIMPLE mode", () => {
    renderWithProviders(
      <AdvancedOnly fallback="fallback-content">only-advanced-content</AdvancedOnly>,
      { uiMode: "SIMPLE" },
    );
    expect(screen.queryByText("only-advanced-content")).not.toBeInTheDocument();
    expect(screen.getByText("fallback-content")).toBeInTheDocument();
  });
});

describe("SimpleOnly", () => {
  it("renders children in SIMPLE mode", () => {
    renderWithProviders(<SimpleOnly>only-simple-content</SimpleOnly>, { uiMode: "SIMPLE" });
    expect(screen.getByText("only-simple-content")).toBeInTheDocument();
  });

  it("renders nothing (no fallback given) in ADVANCED mode", () => {
    renderWithProviders(<SimpleOnly>only-simple-content</SimpleOnly>, { uiMode: "ADVANCED" });
    expect(screen.queryByText("only-simple-content")).not.toBeInTheDocument();
  });

  it("renders the given fallback in ADVANCED mode", () => {
    renderWithProviders(
      <SimpleOnly fallback="fallback-content">only-simple-content</SimpleOnly>,
      { uiMode: "ADVANCED" },
    );
    expect(screen.queryByText("only-simple-content")).not.toBeInTheDocument();
    expect(screen.getByText("fallback-content")).toBeInTheDocument();
  });
});
