/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, signal, Output, EventEmitter, Input, OnInit, computed, effect } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { ApplicationService } from '../../services/application.service';

export interface FilterState {
  trending: boolean;
  categories: string[];
  deploymentTypes: string[];
  capabilities: string[];
  appStatus: string[];
  midpointVersions: number[];
  integrationMethods: string[];
  maintainers: string[];
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
  styleUrls: ['./filter-modal.scss']
})
export class FilterModal implements OnInit {
  @Input() isOpen = signal<boolean>(false);
  @Input() currentFilterState = signal<FilterState>({
    trending: false,
    categories: [],
    deploymentTypes: [],
    capabilities: [],
    appStatus: [],
    midpointVersions: [],
    integrationMethods: [],
    maintainers: []
  });
  @Output() modalClosed = new EventEmitter<void>();
  @Output() filterApplied = new EventEmitter<FilterState>();

  protected selectedSection = signal<string>('trending');

  // Filter state
  protected trending = signal<boolean>(false);
  protected selectedCategories = signal<Set<string>>(new Set());
  protected selectedDeploymentTypes = signal<Set<string>>(new Set());
  protected selectedCapabilities = signal<Set<string>>(new Set());
  protected selectedAppStatus = signal<Set<string>>(new Set());
  protected selectedMidpointVersions = signal<Set<number>>(new Set());
  protected selectedIntegrationMethods = signal<Set<string>>(new Set());
  protected selectedMaintainers = signal<Set<string>>(new Set());

  // Data for filters
  protected readonly categories = signal<CategoryCount[]>([]);
  protected readonly deploymentTypes = signal<CategoryCount[]>([]);
  protected readonly midpointVersions = signal<{ id: number; version: string; versionName: string }[]>([]);
  // Loaded from the capability table via the backend.
  protected readonly capabilities = signal<string[]>([]);
  protected readonly appStatuses = [
    { name: 'IN_REVIEW', displayName: 'In review' },
    { name: 'ACTIVE', displayName: 'Active' },
    { name: 'REQUESTED', displayName: 'Requested' },
    { name: 'WITH_ERROR', displayName: 'With error' }
  ];
  // Loaded from the backend so the modal shows every integration method type,
  // not just a hardcoded subset.
  protected readonly integrationMethods = signal<string[]>([]);
  protected readonly maintainers: string[] = [
    'Evolveum',
    'Community',
    'Partner'
  ];

  constructor(private applicationService: ApplicationService) {
    // Sync internal state when currentFilterState changes
    effect(() => {
      const filterState = this.currentFilterState();
      this.trending.set(filterState.trending);
      this.selectedCategories.set(new Set(filterState.categories));
      this.selectedDeploymentTypes.set(new Set(filterState.deploymentTypes));
      this.selectedCapabilities.set(new Set(filterState.capabilities));
      this.selectedAppStatus.set(new Set(filterState.appStatus));
      this.selectedMidpointVersions.set(new Set(filterState.midpointVersions));
      this.selectedIntegrationMethods.set(new Set(filterState.integrationMethods));
      this.selectedMaintainers.set(new Set(filterState.maintainers));
    });
  }

  ngOnInit(): void {
    this.loadCategories();
    this.loadMidpointVersions();
    this.loadIntegrationMethods();
    this.loadCapabilities();
  }

  protected selectSection(section: string): void {
    this.selectedSection.set(section);
  }

  protected toggleTrending(): void {
    this.trending.set(!this.trending());
    this.closeModal();
  }

  protected toggleCategory(categoryName: string): void {
    const current = new Set(this.selectedCategories());
    if (current.has(categoryName)) {
      current.delete(categoryName);
    } else {
      current.add(categoryName);
    }
    this.selectedCategories.set(current);
    this.closeModal();
  }

  protected toggleDeploymentType(name: string): void {
    const current = new Set(this.selectedDeploymentTypes());
    if (current.has(name)) {
      current.delete(name);
    } else {
      current.add(name);
    }
    this.selectedDeploymentTypes.set(current);
    this.closeModal();
  }

  protected isDeploymentTypeSelected(name: string): boolean {
    return this.selectedDeploymentTypes().has(name);
  }

  protected toggleCapability(capability: string): void {
    const current = new Set(this.selectedCapabilities());
    if (current.has(capability)) {
      current.delete(capability);
    } else {
      current.add(capability);
    }
    this.selectedCapabilities.set(current);
    this.closeModal();
  }

  protected toggleAppStatus(status: string): void {
    const current = new Set(this.selectedAppStatus());
    if (current.has(status)) {
      current.delete(status);
    } else {
      current.add(status);
    }
    this.selectedAppStatus.set(current);
    this.closeModal();
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

  protected toggleMidpointVersion(versionId: number): void {
    const current = new Set(this.selectedMidpointVersions());
    if (current.has(versionId)) {
      current.delete(versionId);
    } else {
      current.add(versionId);
    }
    this.selectedMidpointVersions.set(current);
    this.closeModal();
  }

  protected isMidpointVersionSelected(versionId: number): boolean {
    return this.selectedMidpointVersions().has(versionId);
  }

  protected toggleIntegrationMethod(method: string): void {
    const current = new Set(this.selectedIntegrationMethods());
    if (current.has(method)) {
      current.delete(method);
    } else {
      current.add(method);
    }
    this.selectedIntegrationMethods.set(current);
    this.closeModal();
  }

  protected isIntegrationMethodSelected(method: string): boolean {
    return this.selectedIntegrationMethods().has(method);
  }

  protected toggleMaintainer(maintainer: string): void {
    const current = new Set(this.selectedMaintainers());
    if (current.has(maintainer)) {
      current.delete(maintainer);
    } else {
      current.add(maintainer);
    }
    this.selectedMaintainers.set(current);
    this.closeModal();
  }

  protected isMaintainerSelected(maintainer: string): boolean {
    return this.selectedMaintainers().has(maintainer);
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
      deploymentTypes: Array.from(this.selectedDeploymentTypes()),
      capabilities: Array.from(this.selectedCapabilities()),
      appStatus: Array.from(this.selectedAppStatus()),
      midpointVersions: Array.from(this.selectedMidpointVersions()),
      integrationMethods: Array.from(this.selectedIntegrationMethods()),
      maintainers: Array.from(this.selectedMaintainers())
    };
    this.filterApplied.emit(filterState);
    this.modalClosed.emit();
  }

  private loadCategories(): void {
    // Seed the list from ALL category tags (so unused ones still show with a 0
    // count), then add the actual usage counts from the applications.
    forkJoin({
      tags: this.applicationService.getAllTags(),
      apps: this.applicationService.getAll()
    }).subscribe({
      next: ({ tags, apps }) => {
        // Category and Deployment Type are separate filters, keyed off tagType.
        const categoryMap = new Map<string, CategoryCount>();
        const deploymentMap = new Map<string, CategoryCount>();
        const mapFor = (tagType: string | null) =>
          tagType === 'DEPLOYMENT' ? deploymentMap
          : tagType === 'CATEGORY' ? categoryMap
          : null;

        // Seed both lists from ALL tags so unused entries still show with a 0 count.
        tags.forEach(tag => {
          const map = mapFor(tag.tagType);
          if (map) {
            map.set(tag.name, { name: tag.name, displayName: tag.displayName, count: 0 });
          }
        });

        // Add the actual usage counts from the applications.
        apps.forEach(app => {
          const allTags = [...(app.categories || []), ...(app.tags || [])];
          allTags.forEach(tag => {
            const map = mapFor(tag.tagType);
            if (!map) return;
            const existing = map.get(tag.name);
            if (existing) {
              existing.count++;
            } else {
              // Present on an app but missing from the tag list — include it too.
              map.set(tag.name, { name: tag.name, displayName: tag.displayName, count: 1 });
            }
          });
        });

        const sortByName = (a: CategoryCount, b: CategoryCount) => a.displayName.localeCompare(b.displayName);
        this.categories.set(Array.from(categoryMap.values()).sort(sortByName));
        this.deploymentTypes.set(Array.from(deploymentMap.values()).sort(sortByName));
      },
      error: (err) => {
        console.error('Error loading categories:', err);
      }
    });
  }

  private loadMidpointVersions(): void {
    this.applicationService.getMidpointVersions().subscribe({
      next: (versions) => this.midpointVersions.set(versions),
      error: (err) => console.error('Error loading MidPoint versions:', err)
    });
  }

  private loadIntegrationMethods(): void {
    this.applicationService.getIntegrationMethodTypes().subscribe({
      next: (types) => this.integrationMethods.set(types.map(t => t.displayName)),
      error: (err) => console.error('Error loading integration methods:', err)
    });
  }

  private loadCapabilities(): void {
    this.applicationService.getCapabilities().subscribe({
      next: (caps) => this.capabilities.set(
        [...caps]
          .sort((a, b) => (a.displayOrder ?? Number.MAX_SAFE_INTEGER) - (b.displayOrder ?? Number.MAX_SAFE_INTEGER))
          .map(c => c.name)
      ),
      error: (err) => console.error('Error loading capabilities:', err)
    });
  }
}
