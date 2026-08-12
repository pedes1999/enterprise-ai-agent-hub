import { inject, LOCALE_ID, Pipe, PipeTransform } from '@angular/core';
import { formatDate } from '@angular/common';

/** Formats an ISO-8601 UTC timestamp (as returned by the backend) in the viewer's local timezone -- date + time, no raw offset. */
@Pipe({ name: 'localDateTime', standalone: true })
export class LocalDateTimePipe implements PipeTransform {
  private readonly locale = inject(LOCALE_ID);

  transform(value: string | null | undefined): string {
    if (!value) {
      return '—';
    }
    return formatDate(value, 'MMM d, y, h:mm a', this.locale);
  }
}
