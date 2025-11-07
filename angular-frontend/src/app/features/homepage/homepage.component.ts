import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService, ApplicationCard } from '../../core/http/api.service';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './homepage.component.html',
  styleUrls: ['./homepage.component.css']
})
export class HomeComponent implements OnInit {
  private api = inject(ApiService);

  readonly apps = signal<ApplicationCard[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  // paging/search (tweak as needed)
  readonly page = signal(0);
  readonly size = signal(12);
  readonly totalPages = signal(0);
  readonly q = signal('');

  ngOnInit(): void {
    this.loadApps();
  }

  loadApps(): void {
    this.loading.set(true);
    this.error.set(null);
    const params: any = { page: this.page(), size: this.size(), sort: 'name,asc' };
    if (this.q()) params.q = this.q();

    this.api.getApplications(params).subscribe({
      next: (resp: any) => {
        const content = resp?.content ?? resp ?? [];
        this.apps.set(content);
        this.totalPages.set(resp?.totalPages ?? 1);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(String(e?.message ?? e));
        this.loading.set(false);
      }
    });
  }

  search(): void {
    this.page.set(0);
    this.loadApps();
  }

  next(): void {
    if (this.page() + 1 < this.totalPages()) {
      this.page.set(this.page() + 1);
      this.loadApps();
    }
  }

  prev(): void {
    if (this.page() > 0) {
      this.page.set(this.page() - 1);
      this.loadApps();
    }
  }

  open(app: ApplicationCard): void {
    // later: this.router.navigate(['/applications', app.id]);
    console.log('open', app.id);
  }

  onSearchInput(event: Event) {
  const target = event.target as HTMLInputElement;
  this.q.set(target.value);
}
}
