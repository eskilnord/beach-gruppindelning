import { describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { renderWithProviders } from "../../test/renderWithProviders";
import { NewCustomFieldModal } from "./NewCustomFieldModal";
import { sv } from "../../i18n/sv";
import type { CreateFieldDefinitionRequest, FieldDefinition } from "../../api/types";

const PLAN_ID = "plan-1";

function fieldFor(request: CreateFieldDefinitionRequest): FieldDefinition {
  return {
    id: "field-1",
    activityPlanId: PLAN_ID,
    key: request.key ?? "field1",
    label: request.label ?? "",
    fieldType: request.fieldType ?? "text",
    isStandard: false,
    storageKind: "CUSTOM",
    affectsOptimization: false,
    constraintType: "NONE",
  };
}

describe("NewCustomFieldModal", () => {
  it("posts the expected body, shows a success notification, invalidates field definitions, and selects the new field", async () => {
    let receivedBody: CreateFieldDefinitionRequest | null = null;
    server.use(
      http.post(`/api/plans/${PLAN_ID}/field-definitions`, async ({ request }) => {
        receivedBody = (await request.json()) as CreateFieldDefinitionRequest;
        return HttpResponse.json(fieldFor(receivedBody), { status: 201 });
      }),
    );

    const onClose = vi.fn();
    const onCreated = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <NewCustomFieldModal planId={PLAN_ID} opened onClose={onClose} onCreated={onCreated} />,
    );

    await user.type(screen.getByRole("textbox", { name: sv.importWizard.newFieldModal.nameLabel }), "Vill spela med");
    await user.click(screen.getByRole("button", { name: sv.importWizard.newFieldModal.submit }));

    await waitFor(() => expect(receivedBody).not.toBeNull());
    expect(receivedBody).toMatchObject({ key: "villSpelaMed", label: "Vill spela med", fieldType: "text" });

    await waitFor(() => expect(onCreated).toHaveBeenCalledTimes(1));
    expect(onCreated).toHaveBeenCalledWith(expect.objectContaining({ key: "villSpelaMed" }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(await screen.findByText(sv.importWizard.newFieldModal.createSuccess("Vill spela med"))).toBeInTheDocument();
  });

  it("requires options for a singleSelect field and sends them as a JSON array", async () => {
    let receivedBody: CreateFieldDefinitionRequest | null = null;
    server.use(
      http.post(`/api/plans/${PLAN_ID}/field-definitions`, async ({ request }) => {
        receivedBody = (await request.json()) as CreateFieldDefinitionRequest;
        return HttpResponse.json(fieldFor(receivedBody), { status: 201 });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(<NewCustomFieldModal planId={PLAN_ID} opened onClose={() => {}} />);

    await user.type(screen.getByRole("textbox", { name: sv.importWizard.newFieldModal.nameLabel }), "Storlek");
    await user.click(screen.getByRole("textbox", { name: sv.importWizard.newFieldModal.typeLabel }));
    await user.click(await screen.findByRole("option", { name: sv.importWizard.newFieldModal.types.singleSelect, hidden: true }));

    // No options entered yet -> validation blocks the submit.
    await user.click(screen.getByRole("button", { name: sv.importWizard.newFieldModal.submit }));
    expect(receivedBody).toBeNull();

    await user.type(
      screen.getByRole("textbox", { name: sv.fieldBuilder.newFieldModal.optionsLabel }),
      "Small, Medium, Large",
    );
    await user.click(screen.getByRole("button", { name: sv.importWizard.newFieldModal.submit }));

    await waitFor(() => expect(receivedBody).not.toBeNull());
    expect(receivedBody!.optionsJson).toBe(JSON.stringify(["Small", "Medium", "Large"]));
  });

  it("shows an error notification and keeps the modal open when the create call fails", async () => {
    server.use(
      http.post(`/api/plans/${PLAN_ID}/field-definitions`, () =>
        HttpResponse.json({ error: sv.importWizard.newFieldModal.createFailed }, { status: 500 }),
      ),
    );

    const onClose = vi.fn();
    const onCreated = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <NewCustomFieldModal planId={PLAN_ID} opened onClose={onClose} onCreated={onCreated} />,
    );

    await user.type(screen.getByRole("textbox", { name: sv.importWizard.newFieldModal.nameLabel }), "Vill spela med");
    await user.click(screen.getByRole("button", { name: sv.importWizard.newFieldModal.submit }));

    await waitFor(() => expect(screen.getByText(sv.importWizard.newFieldModal.createFailed)).toBeInTheDocument());
    expect(onClose).not.toHaveBeenCalled();
    expect(onCreated).not.toHaveBeenCalled();
  });
});
