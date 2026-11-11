/** A point on a chart's day axis. */
export interface PlotPoint {
  index: number;
  value: number;
  x: number;
  y: number;
}

/** Horizontal position for a day index inside a padded plot. */
export function xAt(
  index: number,
  count: number,
  width: number,
  padLeft: number,
  padRight: number
): number {
  const inner = width - padLeft - padRight;
  if (count <= 1) return padLeft + inner / 2;
  return padLeft + (index / (count - 1)) * inner;
}

/** Vertical position for a value on a bounded scale. */
export function yAt(
  value: number,
  min: number,
  max: number,
  height: number,
  padTop: number,
  padBottom: number
): number {
  const inner = height - padTop - padBottom;
  if (max === min) return padTop + inner / 2;
  return padTop + ((max - value) / (max - min)) * inner;
}

/**
 * Split points into runs so a missing day is a gap, not a straight line
 * across days that were never recorded.
 */
export function segmentRuns(points: readonly PlotPoint[]): PlotPoint[][] {
  const runs: PlotPoint[][] = [];
  let current: PlotPoint[] = [];
  points.forEach((point, index) => {
    const previous = points[index - 1];
    if (previous && point.index !== previous.index + 1) {
      if (current.length > 0) runs.push(current);
      current = [];
    }
    current.push(point);
  });
  if (current.length > 0) runs.push(current);
  return runs;
}

/** Rectangle for a p25–p75 band in the same coordinate system as `yAt`. */
export function bandRect(
  p25: number,
  p75: number,
  min: number,
  max: number,
  height: number,
  padTop: number,
  padBottom: number
): { y: number; height: number } {
  const top = yAt(p75, min, max, height, padTop, padBottom);
  const bottom = yAt(p25, min, max, height, padTop, padBottom);
  return { y: top, height: Math.max(0, bottom - top) };
}
