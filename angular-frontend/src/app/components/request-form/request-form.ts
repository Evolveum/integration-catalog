import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApplicationService } from '../../services/application.service';
import { CategoryCount } from '../../models/category-count.model';

@Component({
  selector: 'app-request-form',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './request-form.html',
  styleUrl: './request-form.css'
})
export class RequestForm implements OnInit {
  protected loading = signal<boolean>(false);
  protected error = signal<string | null>(null);
  protected categories = signal<CategoryCount[]>([]);
  protected certificationLevels = signal<CategoryCount[]>([]);
  protected appStatuses = signal<CategoryCount[]>([]);
  protected supportedOperations = signal<CategoryCount[]>([]);
  protected isSupportedOperationsExpanded = signal<boolean>(true);
  protected isCategoryExpanded = signal<boolean>(true);
  protected isCertificationLevelExpanded = signal<boolean>(true);
  protected activeMainTab = signal<'public' | 'local'>('public');

  constructor(
    private applicationService: ApplicationService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.loadCategories();
    this.loadCertificationLevels();
    this.loadAppStatuses();
    this.loadSupportedOperations();
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

  private loadCertificationLevels(): void {
    this.applicationService.getCommonTagCounts().subscribe({
      next: (data) => {
        this.certificationLevels.set(data);
      },
      error: (err) => {
        console.error('Error loading certification levels:', err);
      }
    });
  }

  private loadAppStatuses(): void {
    this.applicationService.getAppStatusCounts().subscribe({
      next: (data) => {
        this.appStatuses.set(data);
      },
      error: (err) => {
        console.error('Error loading app statuses:', err);
      }
    });
  }

  private loadSupportedOperations(): void {
    this.applicationService.getSupportedOperationsCounts().subscribe({
      next: (data) => {
        this.supportedOperations.set(data);
      },
      error: (err) => {
        console.error('Error loading supported operations:', err);
      }
    });
  }

  protected goBack(): void {
    this.router.navigate(['/applications']);
  }

  protected clearFilters(): void {
    // Clear app status
    this.clearAppStatus();

    // Clear all individual filter sections
    this.clearSupportedOperations();
    this.clearCategory();
    this.clearCertificationLevel();
  }

  protected clearAppStatus(): void {
    // Uncheck all app status radio buttons
    const radios = document.querySelectorAll<HTMLInputElement>('.app-status-radio');
    radios.forEach(radio => {
      radio.checked = false;
    });
  }

  protected clearSupportedOperations(): void {
    // Uncheck all supported operations checkboxes
    const checkboxes = document.querySelectorAll<HTMLInputElement>('.supported-operations-checkbox');
    checkboxes.forEach(checkbox => {
      checkbox.checked = false;
    });
  }

  protected clearCategory(): void {
    // Uncheck all category checkboxes
    const checkboxes = document.querySelectorAll<HTMLInputElement>('.category-checkbox');
    checkboxes.forEach(checkbox => {
      checkbox.checked = false;
    });
  }

  protected clearCertificationLevel(): void {
    // Uncheck all certification level checkboxes
    const checkboxes = document.querySelectorAll<HTMLInputElement>('.certification-checkbox');
    checkboxes.forEach(checkbox => {
      checkbox.checked = false;
    });
  }

  protected toggleSupportedOperations(): void {
    this.isSupportedOperationsExpanded.update(value => !value);
  }

  protected toggleCategory(): void {
    this.isCategoryExpanded.update(value => !value);
  }

  protected toggleCertificationLevel(): void {
    this.isCertificationLevelExpanded.update(value => !value);
  }

  protected setActiveMainTab(tab: 'public' | 'local'): void {
    this.activeMainTab.set(tab);
  }
}
