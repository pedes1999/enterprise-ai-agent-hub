import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { KnowledgeService } from '../../core/services/knowledge.service';
import { KnowledgeSourceSummary, RetrievedChunkResult } from '../../core/models/knowledge.model';
import { LocalDateTimePipe } from '../../shared/pipes/local-date-time.pipe';
import { extractErrorMessage } from '../../shared/http/error-message';
import { RowMessage } from '../../shared/models/row-message';

@Component({
  selector: 'app-knowledge',
  imports: [FormsModule, LocalDateTimePipe],
  templateUrl: './knowledge.html',
  styleUrl: './knowledge.css',
})
export class Knowledge implements OnInit {
  private readonly knowledgeService = inject(KnowledgeService);

  readonly sources = signal<KnowledgeSourceSummary[]>([]);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);

  readonly newName = signal('');
  readonly newSourceType = signal('upload');
  readonly creating = signal(false);
  readonly createError = signal<string | null>(null);

  readonly selectedFiles: Record<string, File | null> = {};
  readonly uploadingId = signal<string | null>(null);
  readonly uploadMessages: Record<string, RowMessage> = {};

  readonly queryText: Record<string, string> = {};
  readonly queryOpenId = signal<string | null>(null);
  readonly queryingId = signal<string | null>(null);
  readonly queryResults: Record<string, RetrievedChunkResult[]> = {};
  readonly queryError: Record<string, string> = {};

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.knowledgeService.list().subscribe({
      next: (list) => {
        this.sources.set(list);
        this.loading.set(false);
      },
      error: (err) => {
        this.loadError.set(extractErrorMessage(err, 'Failed to load knowledge sources.'));
        this.loading.set(false);
      },
    });
  }

  create(): void {
    const name = this.newName().trim();
    if (!name) {
      return;
    }
    this.creating.set(true);
    this.createError.set(null);
    this.knowledgeService.create({ name, sourceType: this.newSourceType() }).subscribe({
      next: (source) => {
        this.creating.set(false);
        this.newName.set('');
        this.sources.update((list) => [source, ...list]);
      },
      error: (err) => {
        this.creating.set(false);
        this.createError.set(extractErrorMessage(err, 'Failed to create knowledge source.'));
      },
    });
  }

  onFileSelected(sourceId: string, event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFiles[sourceId] = input.files?.[0] ?? null;
  }

  hasSelectedFile(sourceId: string): boolean {
    return !!this.selectedFiles[sourceId];
  }

  upload(sourceId: string): void {
    const file = this.selectedFiles[sourceId];
    if (!file) {
      return;
    }
    this.uploadingId.set(sourceId);
    delete this.uploadMessages[sourceId];
    this.knowledgeService.uploadDocument(sourceId, file).subscribe({
      next: (response) => {
        this.uploadingId.set(null);
        this.selectedFiles[sourceId] = null;
        this.uploadMessages[sourceId] = {
          kind: 'success',
          text: `Uploaded "${response.documentName}" — ${response.chunkCount} chunk${response.chunkCount === 1 ? '' : 's'} added.`,
        };
      },
      error: (err) => {
        this.uploadingId.set(null);
        this.uploadMessages[sourceId] = { kind: 'error', text: extractErrorMessage(err, 'Upload failed.') };
      },
    });
  }

  toggleQuery(sourceId: string): void {
    this.queryOpenId.set(this.queryOpenId() === sourceId ? null : sourceId);
  }

  runQuery(sourceId: string): void {
    const query = (this.queryText[sourceId] ?? '').trim();
    if (!query) {
      return;
    }
    this.queryingId.set(sourceId);
    delete this.queryError[sourceId];
    this.knowledgeService.query(sourceId, { query, topK: null }).subscribe({
      next: (results) => {
        this.queryingId.set(null);
        this.queryResults[sourceId] = results;
      },
      error: (err) => {
        this.queryingId.set(null);
        this.queryError[sourceId] = extractErrorMessage(err, 'Query failed.');
      },
    });
  }
}
