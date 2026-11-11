/**
 * Paginated read used by the CSV/PDF export routes.
 *
 * PostgREST caps a single response (Supabase's default is 1000 rows), so the
 * old `.limit(10000)` was doubly wrong: it promised a cap the API could not
 * return and silently dropped the rest. Android pages until the account is
 * exhausted; so does this helper. `makeQuery` must return a **fresh** builder
 * each call because `.range()` mutates the builder.
 */

export interface ExportPage<T> {
  data: T[] | null;
  error: { message: string } | null;
}

export interface ExportQuery<T> {
  range(from: number, to: number): PromiseLike<ExportPage<T>>;
}

export interface ExportFetchResult<T> {
  data: T[];
  error: string | null;
}

/**
 * Hard stop so a server that ignores `Range` cannot spin forever. 1000 pages
 * of 1000 rows is far beyond a diary's lifetime; hitting it is reported as an
 * error instead of a silent partial file.
 */
export const MAX_EXPORT_PAGES = 1000;

export async function fetchAllExportPages<T>(
  makeQuery: () => ExportQuery<T>,
  pageSize = 1000
): Promise<ExportFetchResult<T>> {
  const rows: T[] = [];

  for (let page = 0; page < MAX_EXPORT_PAGES; page += 1) {
    const start = page * pageSize;
    const { data, error } = await makeQuery().range(start, start + pageSize - 1);

    if (error) return { data: rows, error: error.message };
    if (!data || data.length === 0) return { data: rows, error: null };

    rows.push(...data);
    if (data.length < pageSize) return { data: rows, error: null };
  }

  return {
    data: rows,
    error: `Export exceeded ${MAX_EXPORT_PAGES * pageSize} rows`,
  };
}
