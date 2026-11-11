const MISSING_RPC_CODES = new Set(["PGRST202", "42883"]);

/** Missing RPC names are classified by structured PostgREST/PostgreSQL codes only. */
export function isMissingConditionalSaveRpc(error: unknown): boolean {
  if (!error || typeof error !== "object") return false;
  const code = (error as { code?: unknown }).code;
  return typeof code === "string" && MISSING_RPC_CODES.has(code);
}

/** PostgreSQL data/integrity failures are user-correctable validation errors. */
export function isConditionalSaveValidationError(error: unknown): boolean {
  if (!error || typeof error !== "object") return false;
  const code = (error as { code?: unknown }).code;
  return code === "22023";
}
