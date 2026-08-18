/**
 * A short success/error note pinned to one row of a list, rather than to the
 * page as a whole -- e.g. "Key saved." next to a single credential, or
 * "Upload failed." next to a single knowledge source. Components keep these
 * in a `Record<string, RowMessage>` keyed by whatever identifies the row.
 *
 * Shared because credentials, knowledge, and team each declared their own
 * structurally identical copy of this interface.
 */
export interface RowMessage {
  kind: 'success' | 'error';
  text: string;
}
