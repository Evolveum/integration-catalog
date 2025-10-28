import { Component, OnInit, signal, computed, ViewChild, ViewChildren, ElementRef, AfterViewInit, QueryList } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApplicationService } from '../../services/application.service';
import { Application } from '../../models/application.model';
import { CategoryCount } from '../../models/category-count.model';
import { RequestForm } from '../request-form/request-form';
import { LoginModal } from '../login-modal/login-modal';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-applications-list',
  standalone: true,
  imports: [CommonModule, FormsModule, RequestForm, LoginModal],
  templateUrl: './applications-list.html',
  styleUrls: ['./applications-list.css']
})
export class ApplicationsList implements OnInit, AfterViewInit {
  @ViewChild('scrollContainer') scrollContainer!: ElementRef<HTMLDivElement>;
  @ViewChild('scrollContainerMore') scrollContainerMore!: ElementRef<HTMLDivElement>;
  @ViewChildren('featuredCard') featuredCards!: QueryList<ElementRef<HTMLDivElement>>;

  protected readonly applications = signal<Application[]>([]);
  protected readonly categories = signal<CategoryCount[]>([]);
  protected readonly loading = signal<boolean>(true);
  protected readonly error = signal<string | null>(null);
  protected readonly searchQuery = signal('');
  protected readonly canScrollLeft = signal<boolean>(false);
  protected readonly canScrollRight = signal<boolean>(false);
  protected readonly currentPage = signal<number>(0);
  protected readonly itemsPerPage = 12;
  protected readonly sortBy = signal<'alphabetical' | 'popularity' | 'activity'>('alphabetical');
  protected readonly viewMode = signal<'grid' | 'list'>('grid');
  protected readonly activeTab = signal<string>('all');
  protected readonly isRequestModalOpen = signal<boolean>(false);
  protected readonly isLoginModalOpen = signal<boolean>(false);

  protected readonly featuredApplications = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    const activeTab = this.activeTab();
    const apps = this.applications();
    // Don't show featured apps when searching or filtering by category
    if (query || activeTab !== 'all') {
      return [];
    }
    return apps;
  });

  protected readonly moreApplications = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    const activeTab = this.activeTab();
    let apps = [...this.applications()];

    // Filter by category tab (if not 'all')
    if (activeTab !== 'all') {
      apps = apps.filter(app =>
        app.categories?.some((category: any) => category.displayName === activeTab)
      );
    }

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

  protected readonly filteredCount = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    const activeTab = this.activeTab();
    let apps = this.applications();

    // Filter by category tab (if not 'all')
    if (activeTab !== 'all') {
      apps = apps.filter(app =>
        app.categories?.some((category: any) => category.displayName === activeTab)
      );
    }

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

  protected readonly totalPages = computed(() => {
    return Math.ceil(this.filteredCount() / this.itemsPerPage);
  });

  constructor(
    private applicationService: ApplicationService,
    private router: Router,
    protected authService: AuthService
  ) {}

  ngOnInit(): void {
    this.loadApplications();
    this.loadCategories();
  }

  ngAfterViewInit(): void {
    this.updateScrollButtons();
    setTimeout(() => {
      this.updateCardOpacities();
    }, 0);
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

  protected setActiveTab(tab: string): void {
    this.activeTab.set(tab);
    this.currentPage.set(0);
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
    this.updateCardOpacities();
  }

  private updateScrollButtons(): void {
    const container = this.scrollContainer?.nativeElement;
    if (!container) return;

    this.canScrollLeft.set(container.scrollLeft > 0);
    this.canScrollRight.set(
      container.scrollLeft < container.scrollWidth - container.clientWidth - 1
    );
  }

  private updateCardOpacities(): void {
    const container = this.scrollContainer?.nativeElement;
    if (!container || !this.featuredCards) return;

    const containerRect = container.getBoundingClientRect();
    const containerLeft = containerRect.left;
    const containerRight = containerRect.right;

    this.featuredCards.forEach(cardRef => {
      const card = cardRef.nativeElement;
      const cardRect = card.getBoundingClientRect();
      const cardLeft = cardRect.left;
      const cardRight = cardRect.right;

      // Calculate how much of the card is visible
      const visibleLeft = Math.max(cardLeft, containerLeft);
      const visibleRight = Math.min(cardRight, containerRight);
      const visibleWidth = Math.max(0, visibleRight - visibleLeft);
      const cardWidth = cardRect.width;
      const visibilityRatio = visibleWidth / cardWidth;

      // If card is fully visible (or almost fully), opacity is 1, otherwise 0.5
      if (visibilityRatio >= 0.95) {
        card.style.opacity = '1';
      } else {
        card.style.opacity = '0.5';
      }
    });
  }

  protected navigateToDetail(id: string): void {
    this.router.navigate(['/applications', id]);
  }

  protected openRequestModal(): void {
    this.isRequestModalOpen.set(true);
  }

  protected closeRequestModal(): void {
    this.isRequestModalOpen.set(false);
  }

  protected openLoginModal(): void {
    this.isLoginModalOpen.set(true);
  }

  protected closeLoginModal(): void {
    this.isLoginModalOpen.set(false);
  }

  protected logout(): void {
    this.authService.logout();
  }

  protected voteForRequest(app: Application): void {
    const currentUser = this.authService.getCurrentUser()();

    if (!currentUser) {
      alert('Please log in to vote');
      return;
    }

    if (!app.requestId) {
      alert('Request ID not found');
      return;
    }

    this.applicationService.submitVote(app.requestId, currentUser).subscribe({
      next: () => {
        // Increment vote count locally
        app.voteCount = (app.voteCount || 0) + 1;
      },
      error: (err) => {
        if (err.status === 400) {
          alert('You have already voted for this request');
        } else {
          alert('Failed to submit vote. Please try again.');
        }
        console.error('Error submitting vote:', err);
      }
    });
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
          this.updateCardOpacities();
        }, 0);
      },
      error: (err) => {
        this.error.set('Failed to load applications');
        this.loading.set(false);
        console.error('Error loading applications:', err);
      }
    });
  }

  private loadCategories(): void {
    this.applicationService.getCategoryCounts().subscribe({
      next: (data) => {
        this.categories.set(data);
      },
      error: (err) => {
        console.error('Error loading categories:', err);
      }
    });
  }
}
