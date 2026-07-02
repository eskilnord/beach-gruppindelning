import { useQuery } from "@tanstack/react-query";
import { api } from "./client";
import type { Person } from "./types";

const personsKey = ["persons"] as const;

/** All known persons (spec §7.1), used to resolve import-wizard person-match proposals and to join
 *  participant names on the basic Deltagare tab (M4 replaces this with a proper Deltagarvy). */
export function usePersons() {
  return useQuery({
    queryKey: personsKey,
    queryFn: () => api.get<Person[]>("/api/persons"),
  });
}
