import { useQuery } from "@tanstack/react-query";
import { api } from "./client";

export interface HealthResponse {
  status: string;
}

/**
 * Backend-status poll for the shell footer (docs/design/01-architecture.md §4 failure UX, browser
 * mode variant): polls GET /api/health every 5s. `isError` drives the reconnect overlay; a
 * successful response after failures naturally clears it because TanStack Query resets `isError`.
 */
export function useBackendHealth() {
  return useQuery({
    queryKey: ["health"],
    queryFn: () => api.get<HealthResponse>("/api/health"),
    refetchInterval: 5000,
    retry: false,
  });
}
