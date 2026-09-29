/**
 * Night bucketing. A "night" is labelled by the calendar date on which it starts, in a fixed
 * UTC offset (default WAT, +01:00), with the boundary at local NOON: 23:30 on Oct 3 and
 * 06:10 on Oct 4 both belong to night "2026-10-03". The rig's own time zone is not on-chain
 * (by design, docs/PRIVACY.md), so one offset is used for everyone and reported with the data.
 */
export const DAY = 86_400;
const NOON = 12 * 3600;

export const MIN_TZ_OFFSET = -12 * 60;
export const MAX_TZ_OFFSET = 14 * 60;

export function assertTzOffset(minutes: number): number {
  if (!Number.isInteger(minutes) || minutes < MIN_TZ_OFFSET || minutes > MAX_TZ_OFFSET) {
    throw new RangeError(`tz offset must be an integer number of minutes in [${MIN_TZ_OFFSET}, ${MAX_TZ_OFFSET}]`);
  }
  return minutes;
}

/** Day index (days since 1970-01-01) of the night containing unix time `ts`. */
export function nightIndex(ts: number, tzOffsetMinutes: number): number {
  return Math.floor((ts + tzOffsetMinutes * 60 - NOON) / DAY);
}

export function dayLabel(dayIndex: number): string {
  return new Date(dayIndex * DAY * 1000).toISOString().slice(0, 10);
}

export function nightOf(ts: number, tzOffsetMinutes: number): string {
  return dayLabel(nightIndex(ts, tzOffsetMinutes));
}

/** Local hour of day (0..23) of unix time `ts`. */
export function hourOf(ts: number, tzOffsetMinutes: number): number {
  const local = ts + tzOffsetMinutes * 60;
  return Math.floor((((local % DAY) + DAY) % DAY) / 3600);
}

/** UTC calendar month "YYYY-MM". */
export function monthOf(ts: number): string {
  return new Date(ts * 1000).toISOString().slice(0, 7);
}

export function formatTzOffset(minutes: number): string {
  const sign = minutes < 0 ? "-" : "+";
  const m = Math.abs(minutes);
  return `${sign}${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
}

export function median(xs: number[]): number | null {
  if (xs.length === 0) return null;
  const s = [...xs].sort((a, b) => a - b);
  const mid = s.length >> 1;
  return s.length % 2 ? s[mid]! : (s[mid - 1]! + s[mid]!) / 2;
}
