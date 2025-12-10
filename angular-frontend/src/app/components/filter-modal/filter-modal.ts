/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, signal, Output, EventEmitter, Input, OnInit, computed, effect } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApplicationService } from '../../services/application.service';

export interface FilterState {
  trending: boolean;
  categories: string[];
  capabilities: string[];
  appStatus: string[];
  midpointVersions: string[];
  integrationMethods: string[];
}

interface CategoryCount {
  name: string;
  displayName: string;
  count: number;
}

@Component({
  selector: 'app-filter-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './filter-modal.html',
  styleUrls: ['./filter-modal.css']
})
export class FilterModal implements OnInit {
  @Input() isOpen = signal<boolean>(false);
  @Input() currentFilterState = signal<FilterState>({
    trending: false,
    categories: [],
    capabilities: [],
    appStatus: [],
    midpointVersions: [],
    integrationMethods: []
  });
  @Output() modalClosed = new EventEmitter<void>();
  @Output() filterApplied = new EventEmitter<FilterState>();

  protected selectedSection = signal<string>('trending');

  // Filter state
  protected trending = signal<boolean>(false);
  protected selectedCategories = signal<Set<string>>(new Set());
  protected selectedCapabilities = signal<Set<string>>(new Set());
  protected selectedAppStatus = signal<Set<string>>(new Set());
  protected selectedMidpointVersions = signal<Set<string>>(new Set());
  protected selectedIntegrationMethods = signal<Set<string>>(new Set());

  // Data for filters
  protected readonly categories = signal<CategoryCount[]>([]);
  protected readonly midpointVersions = signal<string[]>([]);
  protected readonly capabilities: string[] = [
    'CREATE',
    'GET',
    'UPDATE',
    'DELETE',
    'TEST',
    'SCRIPT_ON_CONNECTOR',
    'SCRIPT_ON_RESOURCE',
    'AUTHENTICATION',
    'SEARCH',
    'VALIDATE',
    'SYNC',
    'LIVE_SYNC',
    'SCHEMA',
    'DISCOVER_CONFIGURATION',
    'RESOLVE_USERNAME',
    'PARTIAL_SCHEMA',
    'COMPLEX_UPDATE_DELTA',
    'UPDATE_DELTA'
  ];
  protected readonly appStatuses = [
    { name: 'IN_PUBLISH_PROCESS', displayName: 'In publish process' },
    { name: 'ACTIVE', displayName: 'Active' },
    { name: 'REQUESTED', displayName: 'Requested' },
    { name: 'WITH_ERROR', displayName: 'With error' }
  ];
  protected readonly integrationMethods: string[] = [
    'SCIM',
    'openLDAP',
    'REST API',
    'CSV file import'
  ];

  constructor(private applicationService: ApplicationService) {
    // Sync internal state when currentFilterState changes
    effect(() => {
      const filterState = this.currentFilterState();
      this.trending.set(filterState.trending);
      this.selectedCategories.set(new Set(filterState.categories));
      this.selectedCapabilities.set(new Set(filterState.capabilities));
      this.selectedAppStatus.set(new Set(filterState.appStatus));
      this.selectedMidpointVersions.set(new Set(filterState.midpointVersions));
      this.selectedIntegrationMethods.set(new Set(filterState.integrationMethods));
    });
  }

  ngOnInit(): void {
    this.loadCategories();
    this.loadMidpointVersions();
  }

  protected selectSection(section: string): void {
    this.selectedSection.set(section);
  }

  protected toggleTrending(): void {
    this.trending.set(!this.trending());
  }

  protected toggleCategory(categoryName: string): void {
    const current = new Set(this.selectedCategories());
    if (current.has(categoryName)) {
      current.delete(categoryName);
    } else {
      current.add(categoryName);
    }
    this.selectedCategories.set(current);
  }

  protected toggleCapability(capability: string): void {
    const current = new Set(this.selectedCapabilities());
    if (current.has(capability)) {
      current.delete(capability);
    } else {
      current.add(capability);
    }
    this.selectedCapabilities.set(current);
  }

  protected toggleAppStatus(status: string): void {
    const current = new Set(this.selectedAppStatus());
    if (current.has(status)) {
      current.delete(status);
    } else {
      current.add(status);
    }
    this.selectedAppStatus.set(current);
  }

  protected isCategorySelected(categoryName: string): boolean {
    return this.selectedCategories().has(categoryName);
  }

  protected isCapabilitySelected(capability: string): boolean {
    return this.selectedCapabilities().has(capability);
  }

  protected isAppStatusSelected(status: string): boolean {
    return this.selectedAppStatus().has(status);
  }

  protected toggleMidpointVersion(version: string): void {
    const current = new Set(this.selectedMidpointVersions());
    if (current.has(version)) {
      current.delete(version);
    } else {
      current.add(version);
    }
    this.selectedMidpointVersions.set(current);
  }

  protected isMidpointVersionSelected(version: string): boolean {
    return this.selectedMidpointVersions().has(version);
  }

  protected toggleIntegrationMethod(method: string): void {
    const current = new Set(this.selectedIntegrationMethods());
    if (current.has(method)) {
      current.delete(method);
    } else {
      current.add(method);
    }
    this.selectedIntegrationMethods.set(current);
  }

  protected isIntegrationMethodSelected(method: string): boolean {
    return this.selectedIntegrationMethods().has(method);
  }

  protected formatCapability(capability: string): string {
    return capability
      .split('_')
      .map(word => word.charAt(0) + word.slice(1).toLowerCase())
      .join(' ');
  }

  protected closeModal(): void {
    // Apply filters when closing
    const filterState: FilterState = {
      trending: this.trending(),
      categories: Array.from(this.selectedCategories()),
      capabilities: Array.from(this.selectedCapabilities()),
      appStatus: Array.from(this.selectedAppStatus()),
      midpointVersions: Array.from(this.selectedMidpointVersions()),
      integrationMethods: Array.from(this.selectedIntegrationMethods())
    };
    this.filterApplied.emit(filterState);
    this.modalClosed.emit();
  }

  private loadCategories(): void {
    // Load categories with counts from applications
    this.applicationService.getAll().subscribe({
      next: (apps) => {
        const categoryMap = new Map<string, CategoryCount>();
        apps.forEach(app => {
          // Check both categories and tags fields
          const allTags = [...(app.categories || []), ...(app.tags || [])];
          allTags.forEach(tag => {
            if (tag.tagType === 'CATEGORY' || tag.tagType === 'DEPLOYMENT') {
              if (categoryMap.has(tag.name)) {
                const existing = categoryMap.get(tag.name)!;
                existing.count++;
              } else {
                categoryMap.set(tag.name, {
                  name: tag.name,
                  displayName: tag.displayName,
                  count: 1
                });
              }
            }
          });
        });
        this.categories.set(Array.from(categoryMap.values()));
        console.log('Loaded categories:', Array.from(categoryMap.values()));
      },
      error: (err) => {
        console.error('Error loading categories:', err);
      }
    });
  }

  private loadMidpointVersions(): void {
    // Load unique midpoint versions from all applications
    this.applicationService.getAll().subscribe({
      next: (apps) => {
        const versionsSet = new Set<string>();
        apps.forEach(app => {
          if (app.midpointVersions) {
            app.midpointVersions.forEach(version => versionsSet.add(version));
          }
        });
        const sortedVersions = Array.from(versionsSet).sort();
        this.midpointVersions.set(sortedVersions);
      },
      error: (err) => {
        console.error('Error loading midpoint versions:', err);
      }
    });
  }
}
