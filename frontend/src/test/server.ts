import { setupServer } from "msw/node";

/**
 * Shared MSW server for component tests. No default handlers: each test registers exactly the
 * routes it needs via `server.use(...)`, so an unhandled request fails loudly instead of silently
 * falling through.
 */
export const server = setupServer();
