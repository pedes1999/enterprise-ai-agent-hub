import { LocalDateTimePipe } from './local-date-time.pipe';
import { TestBed } from '@angular/core/testing';

describe('LocalDateTimePipe', () => {
  function createPipe(): LocalDateTimePipe {
    return TestBed.runInInjectionContext(() => new LocalDateTimePipe());
  }

  it('returns an em dash for null/undefined', () => {
    const pipe = createPipe();
    expect(pipe.transform(null)).toBe('—');
    expect(pipe.transform(undefined)).toBe('—');
  });

  it('formats an ISO timestamp as a human-readable local date and time', () => {
    const pipe = createPipe();
    const result = pipe.transform('2026-08-12T20:04:04.947056Z');
    // Exact clock time depends on the test runner's local timezone, but it
    // must never be the raw ISO string, and must include the year and a
    // colon-separated time (not just a bare date).
    expect(result).not.toContain('T');
    expect(result).not.toContain('Z');
    expect(result).toContain('2026');
    expect(result).toMatch(/\d{1,2}:\d{2}/);
  });
});
