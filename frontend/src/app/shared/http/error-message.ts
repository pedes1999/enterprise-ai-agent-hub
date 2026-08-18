import { HttpErrorResponse } from '@angular/common/http';

/**
 * Pulls the backend's own error text out of a failed HttpClient call,
 * falling back to a caller-supplied message when there isn't one.
 *
 * Every gateway-api error response carries a JSON `{ "message": ... }` body
 * (see GlobalExceptionHandler / ApiError), so `error.error.message` is the
 * message a user should actually see. The fallback covers the cases where
 * there is no such body at all -- a network failure, a CORS rejection, or a
 * 502 from something in front of the API -- where `error.error` is a
 * ProgressEvent or a plain string rather than the expected shape.
 *
 * Extracted because all ten feature components were repeating the same
 * `err.error?.message ?? '...'` expression inline (23 call sites), each one
 * independently re-deciding how to reach into an error object and typing
 * the parameter as `any` to do it.
 */
export function extractErrorMessage(error: unknown, fallback: string): string {
  const body = error instanceof HttpErrorResponse ? error.error : (error as { error?: unknown } | null)?.error;
  if (body && typeof body === 'object' && typeof (body as { message?: unknown }).message === 'string') {
    return (body as { message: string }).message;
  }
  return fallback;
}
