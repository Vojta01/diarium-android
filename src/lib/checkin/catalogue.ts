/**
 * Catalogue mutations (activities and habits) are server-first.
 *
 * A failed add must not leave a phantom definition in the list: the dialog
 * stays open with the typed value, and a retry is not rejected as a duplicate
 * because nothing was inserted locally. The same rule applies to remove and
 * restore.
 */
export interface DefinitionLike {
  key: string;
  label: string;
}

/** Stable key derived from a user-entered name, matching both API routes. */
export function definitionKey(name: string): string {
  return name.trim().toLowerCase().replace(/\s+/g, "_");
}

/**
 * True when the name collides with an existing definition by key or by
 * case-insensitive label. `exceptKey` lets an edit keep its own name.
 */
export function isDuplicateDefinition(
  definitions: readonly DefinitionLike[],
  name: string,
  exceptKey?: string
): boolean {
  const key = definitionKey(name);
  const label = name.trim().toLowerCase();
  return definitions.some(
    (definition) =>
      definition.key !== exceptKey &&
      (definition.key === key ||
        definition.label.trim().toLowerCase() === label)
  );
}

export interface CatalogueMutation {
  /** Performs the POST. */
  request: () => Promise<Response>;
  /** Runs only after a 2xx response, so no optimistic phantom survives. */
  apply: (body: unknown) => void;
  /** i18n key for a non-2xx response. */
  errorCode: string;
  /** i18n key for a network/parse failure. */
  networkCode: string;
}

/**
 * Run one catalogue mutation and return `null` on success or an i18n key on
 * failure. `apply` is never called for a non-2xx response or a thrown fetch.
 */
export async function mutateCatalogue(
  mutation: CatalogueMutation
): Promise<string | null> {
  let response: Response;
  try {
    response = await mutation.request();
  } catch {
    return mutation.networkCode;
  }

  if (!response.ok) return mutation.errorCode;

  const body = await response.json().catch(() => null);
  mutation.apply(body);
  return null;
}
