import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { DemoDataResult } from "./types";

const seasonsKey = ["seasons"] as const;

/**
 * WI-4 ("Ha demo-data för beachvolley så att man kan dema det utan att importera en excelfil."):
 * creates a brand-new, fully populated demo season+plan in one call. Safe to call repeatedly — the
 * backend numbers the season name rather than failing when a demo season already exists.
 */
export function useCreateDemoData() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<DemoDataResult>("/api/demo"),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: seasonsKey });
    },
  });
}
