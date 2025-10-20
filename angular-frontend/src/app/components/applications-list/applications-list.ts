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
  protected sortBy = signal<'alphabetical' | 'popularity' | 'activity'>('alphabetical');
  protected viewMode = signal<'grid' | 'list'>('grid');

  protected featuredApplications = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    const apps = this.applications();
    // Don't show featured apps when searching
    if (query) {
      return [];
    }
    return apps;
  });

  protected moreApplications = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    let apps = [...this.applications()];

    // When searching, show all matching apps. When not searching, show all apps
    if (query) {
      apps = apps.filter(app =>
        app.displayName.toLowerCase().includes(query) ||
        app.description.toLowerCase().includes(query) ||
        app.lifecycleState?.toLowerCase().includes(query) ||
        app.riskLevel?.toLowerCase().includes(query) ||
        app.tags?.some(tag =>
          tag.name.toLowerCase().includes(query) ||
          tag.displayName.toLowerCase().includes(query)
        )
      );
    }

    // Sort based on selected option
    const sortOption = this.sortBy();
    if (sortOption === 'alphabetical') {
      apps.sort((a, b) => a.displayName.localeCompare(b.displayName));
    } else if (sortOption === 'popularity') {
      apps.sort((a, b) => {
        const aIsPopular = a.tags?.some(tag => tag.name === 'popular') ? 1 : 0;
        const bIsPopular = b.tags?.some(tag => tag.name === 'popular') ? 1 : 0;
        return bIsPopular - aIsPopular;
      });
    } else if (sortOption === 'activity') {
      apps.sort((a, b) => {
        const aIsActive = a.lifecycleState === 'ACTIVE' ? 1 : 0;
        const bIsActive = b.lifecycleState === 'ACTIVE' ? 1 : 0;
        return bIsActive - aIsActive;
      });
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
        app.description.toLowerCase().includes(query) ||
        app.lifecycleState?.toLowerCase().includes(query) ||
        app.riskLevel?.toLowerCase().includes(query) ||
        app.tags?.some(tag =>
          tag.name.toLowerCase().includes(query) ||
          tag.displayName.toLowerCase().includes(query)
        )
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
    const value = (event.target as HTMLSelectElement).value as 'alphabetical' | 'popularity' | 'activity';
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

  protected navigateToRequest(): void {
    this.router.navigate(['/request']);
  }

  protected formatLifecycleState(state: string | null): string {
    if (!state) return '';

    switch (state) {
      case 'REQUESTED':
        return 'Requested';
      case 'ACTIVE':
        return 'Active';
      case 'WITH_ERROR':
        return 'With error';
      case 'IN_PUBLISH_PROCESS':
        return 'Publishing...';
      default:
        return state;
    }
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
