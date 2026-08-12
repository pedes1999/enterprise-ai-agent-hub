import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { UsageIndicator, USAGE_POLL_INTERVAL_MS } from './usage-indicator';
import { environment } from '../../../../environments/environment';

describe('UsageIndicator', () => {
  let httpMock: HttpTestingController;

  afterEach(() => {
    vi.useRealTimers();
    httpMock.verify();
  });

  function createComponent() {
    TestBed.configureTestingModule({
      imports: [UsageIndicator],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(UsageIndicator);
    fixture.detectChanges();
    return fixture;
  }

  it('fetches usage immediately and renders it', async () => {
    vi.useFakeTimers();
    const fixture = createComponent();

    await vi.advanceTimersByTimeAsync(0);
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/usage`).flush({ active: 2, limit: 5 });
    fixture.detectChanges();

    expect(fixture.componentInstance.usage()).toEqual({ active: 2, limit: 5 });
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('2 / 5 executions running');

    fixture.destroy();
  });

  it('polls again at the configured interval', async () => {
    vi.useFakeTimers();
    const fixture = createComponent();

    await vi.advanceTimersByTimeAsync(0);
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/usage`).flush({ active: 1, limit: 5 });

    await vi.advanceTimersByTimeAsync(USAGE_POLL_INTERVAL_MS);
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/usage`).flush({ active: 3, limit: 5 });
    expect(fixture.componentInstance.usage()).toEqual({ active: 3, limit: 5 });

    fixture.destroy();
  });

  it('stops polling once the component is destroyed', async () => {
    vi.useFakeTimers();
    const fixture = createComponent();

    await vi.advanceTimersByTimeAsync(0);
    httpMock.expectOne(`${environment.apiBaseUrl}/agents/executions/usage`).flush({ active: 1, limit: 5 });

    fixture.destroy();

    await vi.advanceTimersByTimeAsync(USAGE_POLL_INTERVAL_MS * 3);
    httpMock.expectNone(`${environment.apiBaseUrl}/agents/executions/usage`);
  });
});
