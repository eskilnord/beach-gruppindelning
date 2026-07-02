import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { CreateFieldDefinitionRequest, FieldDefinition, UpdateFieldDefinitionRequest } from "./types";

const fieldDefinitionsKey = (planId: string) => ["plans", planId, "field-definitions"] as const;

/** Field definitions visible to a plan (spec §9) — global standard fields plus the plan's own
 *  custom fields (Fältbyggaren CRUD, M4); also used by the import mapping step to offer
 *  "customField:<key>" targets for CUSTOM-storage fields. */
export function useFieldDefinitions(planId: string | undefined) {
  return useQuery({
    queryKey: fieldDefinitionsKey(planId ?? ""),
    queryFn: () => api.get<FieldDefinition[]>(`/api/plans/${planId}/field-definitions`),
    enabled: planId !== undefined,
  });
}

/** Creates a plan-scoped CUSTOM field (Fältbyggaren "Nytt fält", spec §9.1/§19.5). */
export function useCreateFieldDefinition(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateFieldDefinitionRequest) =>
      api.post<FieldDefinition>(`/api/plans/${planId}/field-definitions`, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: fieldDefinitionsKey(planId) });
    },
  });
}

/** Edits a field. Standard/global fields only accept the optimization-facing subset of `body`
 *  (backend enforces this - see FieldDefinitionController); custom fields accept everything. */
export function useUpdateFieldDefinition(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateFieldDefinitionRequest }) =>
      api.patch<FieldDefinition>(`/api/field-definitions/${id}`, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: fieldDefinitionsKey(planId) });
    },
  });
}

/** Deletes a CUSTOM field (standard/global fields are permanent - backend rejects with 400). */
export function useDeleteFieldDefinition(planId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/api/field-definitions/${id}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: fieldDefinitionsKey(planId) });
    },
  });
}
