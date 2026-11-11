/** The one inference vocabulary shared by every analysis module. */
export type Verdict = "higher" | "lower" | "not_clear" | "insufficient";

export interface DiffEstimate {
  nA: number;
  nB: number;
  meanA: number | null;
  meanB: number | null;
  diff: number | null;
  /** Standard error of the difference; null when it cannot be estimated. */
  se: number | null;
  /** Welch–Satterthwaite degrees of freedom. */
  df: number | null;
  ciLow: number | null;
  ciHigh: number | null;
  /** Both groups are constant, so the CI is undefined rather than zero-width. */
  degenerate: boolean;
  verdict: Verdict;
}

/**
 * Two-sided 97.5 % Student-t critical values, indexed by degrees of freedom.
 *
 * The table is used exactly as written (floor(df), clamped to 1..30); above 30
 * the normal value 1.96 applies. No interpolation and no normal approximation
 * for small samples: both would overstate certainty.
 */
const T_CRIT_975: readonly number[] = [
  // df = 0 is a placeholder; df is clamped to at least 1.
  0,
  12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
  2.201, 2.179, 2.16, 2.145, 2.131, 2.12, 2.11, 2.101, 2.093, 2.086,
  2.08, 2.074, 2.069, 2.064, 2.06, 2.056, 2.052, 2.048, 2.045, 2.042,
];

export function tCrit975(df: number): number {
  if (!Number.isFinite(df) || df > 30) return 1.96;
  const bounded = Math.max(1, Math.floor(df));
  return T_CRIT_975[bounded] ?? 1.96;
}

function mean(values: readonly number[]): number | null {
  if (values.length === 0) return null;
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

/** Sample variance (n-1 denominator); null for fewer than two observations. */
function variance(values: readonly number[]): number | null {
  if (values.length < 2) return null;
  const average = mean(values);
  if (average === null) return null;
  const squared = values.reduce(
    (sum, value) => sum + (value - average) ** 2,
    0
  );
  return squared / (values.length - 1);
}

function emptyEstimate(nA: number, nB: number): DiffEstimate {
  return {
    nA,
    nB,
    meanA: null,
    meanB: null,
    diff: null,
    se: null,
    df: null,
    ciLow: null,
    ciHigh: null,
    degenerate: false,
    verdict: "insufficient",
  };
}

/**
 * Welch's unequal-variance difference with a 95 % CI.
 *
 * `minN` is the caller's sample-size gate: period comparison requires 3 per
 * side, impact requires 5 per side. Below the gate means are still returned
 * (they are facts), but no SE, df or CI is invented.
 */
export function welchDiff(
  a: readonly number[],
  b: readonly number[],
  minN: number
): DiffEstimate {
  const nA = a.length;
  const nB = b.length;
  const meanA = mean(a);
  const meanB = mean(b);

  if (nA === 0 || nB === 0) {
    return {
      ...emptyEstimate(nA, nB),
      meanA,
      meanB,
      diff: meanA === null || meanB === null ? null : meanA - meanB,
    };
  }

  const diff = (meanA as number) - (meanB as number);

  if (nA < minN || nB < minN) {
    return {
      ...emptyEstimate(nA, nB),
      meanA,
      meanB,
      diff,
    };
  }

  const varA = variance(a);
  const varB = variance(b);
  if (varA === null || varB === null) {
    return { ...emptyEstimate(nA, nB), meanA, meanB, diff };
  }

  const termA = varA / nA;
  const termB = varB / nB;
  const se = Math.sqrt(termA + termB);

  if (!Number.isFinite(se) || se === 0) {
    return {
      nA,
      nB,
      meanA,
      meanB,
      diff,
      se: 0,
      df: null,
      ciLow: null,
      ciHigh: null,
      degenerate: true,
      verdict: diff === 0 ? "not_clear" : diff > 0 ? "higher" : "lower",
    };
  }

  const denominator =
    (termA * termA) / Math.max(1, nA - 1) +
    (termB * termB) / Math.max(1, nB - 1);
  const df = denominator === 0 ? null : (termA + termB) ** 2 / denominator;
  if (df === null) {
    return { ...emptyEstimate(nA, nB), meanA, meanB, diff };
  }

  const margin = tCrit975(df) * se;
  const ciLow = diff - margin;
  const ciHigh = diff + margin;

  const verdict: Verdict =
    ciLow > 0 ? "higher" : ciHigh < 0 ? "lower" : "not_clear";

  return {
    nA,
    nB,
    meanA,
    meanB,
    diff,
    se,
    df,
    ciLow,
    ciHigh,
    degenerate: false,
    verdict,
  };
}
