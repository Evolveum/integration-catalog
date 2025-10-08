import { Component, OnInit, signal, computed, ViewChild, ElementRef, AfterViewInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApplicationService } from '../../services/application.service';
import { Application } from '../../models/application.model';

@Component({
  selector: 'app-applications-list',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './applications-list.html',
  styleUrl: './applications-list.css'
})
export class ApplicationsList implements OnInit, AfterViewInit {
  @ViewChild('scrollContainer') scrollContainer!: ElementRef<HTMLDivElement>;
  @ViewChild('scrollContainerMore') scrollContainerMore!: ElementRef<HTMLDivElement>;

  protected applications = signal<Application[]>([]);
  protected loading = signal<boolean>(true);
  protected error = signal<string | null>(null);
  protected searchQuery = signal('');
  protected canScrollLeft = signal<boolean>(false);
  protected canScrollRight = signal<boolean>(false);
  protected currentPage = signal<number>(0);
  protected itemsPerPage = 12;
  protected sortBy = signal<'alphabetical'>('alphabetical');
  protected viewMode = signal<'grid' | 'list'>('grid');

  protected featuredApplications = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    const apps = this.applications();
    if (!query) {
      return apps;
    }
    return apps.filter(app =>
      app.displayName.toLowerCase().includes(query) ||
      app.description.toLowerCase().includes(query)
    );
  });

  protected moreApplications = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    let apps = [...this.applications()];

    if (query) {
      apps = apps.filter(app =>
        app.displayName.toLowerCase().includes(query) ||
        app.description.toLowerCase().includes(query)
      );
    }

    // Sort alphabetically
    if (this.sortBy() === 'alphabetical') {
      apps.sort((a, b) => a.displayName.localeCompare(b.displayName));
    }

    const start = this.currentPage() * this.itemsPerPage;
    const end = start + this.itemsPerPage;
    return apps.slice(start, end);
  });

  protected filteredCount = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    let apps = this.applications();

    if (query) {
      apps = apps.filter(app =>
        app.displayName.toLowerCase().includes(query) ||
        app.description.toLowerCase().includes(query)
      );
    }

    return apps.length;
  });

  protected totalPages = computed(() => {
    return Math.ceil(this.filteredCount() / this.itemsPerPage);
  });

  constructor(
    private applicationService: ApplicationService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.loadApplications();
  }

  ngAfterViewInit(): void {
    this.updateScrollButtons();
  }

  protected onSearchChange(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.searchQuery.set(value);
    this.currentPage.set(0);
  }

  protected onSortChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value as 'alphabetical';
    this.sortBy.set(value);
    this.currentPage.set(0);
  }

  protected setViewMode(mode: 'grid' | 'list'): void {
    this.viewMode.set(mode);
  }

  protected scrollLeft(): void {
    const container = this.scrollContainer.nativeElement;
    container.scrollBy({ left: -300, behavior: 'smooth' });
  }

  protected scrollRight(): void {
    const container = this.scrollContainer.nativeElement;
    container.scrollBy({ left: 300, behavior: 'smooth' });
  }

  protected nextPage(): void {
    if (this.currentPage() < this.totalPages() - 1) {
      this.currentPage.update(p => p + 1);
    }
  }

  protected previousPage(): void {
    if (this.currentPage() > 0) {
      this.currentPage.update(p => p - 1);
    }
  }

  protected goToPage(page: number): void {
    this.currentPage.set(page);
  }

  protected onScroll(): void {
    this.updateScrollButtons();
  }

  private updateScrollButtons(): void {
    const container = this.scrollContainer?.nativeElement;
    if (!container) return;

    this.canScrollLeft.set(container.scrollLeft > 0);
    this.canScrollRight.set(
      container.scrollLeft < container.scrollWidth - container.clientWidth - 1
    );
  }

  protected navigateToDetail(id: string): void {
    this.router.navigate(['/applications', id]);
  }

  private loadApplications(): void {
    this.applicationService.getAll().subscribe({
      next: (data) => {
        // console.log('Received applications:', data);
        // console.log('First app lifecycle_state:', data[0]?.lifecycle_state);
        this.applications.set(data);
        this.loading.set(false);
        setTimeout(() => {
          this.updateScrollButtons();
        }, 0);
      },
      error: (err) => {
        this.error.set('Failed to load applications');
        this.loading.set(false);
        console.error('Error loading applications:', err);
      }
    });
  }
}
