import { Component, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { ApplicationService } from '../../services/application.service';
import { ApplicationDetail as ApplicationDetailModel } from '../../models/application-detail.model';

@Component({
  selector: 'app-application-detail',
  imports: [CommonModule],
  templateUrl: './application-detail.html',
  styleUrl: './application-detail.css'
})
export class ApplicationDetail implements OnInit {
  protected application = signal<ApplicationDetailModel | null>(null);
  protected loading = signal<boolean>(true);
  protected error = signal<string | null>(null);
  protected expandedVersions = new Set<number>();
  protected activeEvolvumVersions = signal<any[]>([]);
  protected activeCommunityVersions = signal<any[]>([]);
  protected otherEvolvumVersions = signal<any[]>([]);
  protected otherCommunityVersions = signal<any[]>([]);
  protected activeTab = signal<'main' | 'other'>('main');

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private applicationService: ApplicationService
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.loadApplication(id);
    } else {
      this.error.set('No application ID provided');
      this.loading.set(false);
    }
  }

  protected goBack(): void {
    this.router.navigate(['/applications']);
  }

  protected toggleCapabilities(versionIndex: number): void {
    if (this.expandedVersions.has(versionIndex)) {
      this.expandedVersions.delete(versionIndex);
    } else {
      this.expandedVersions.add(versionIndex);
    }
  }

  protected isExpanded(versionIndex: number): boolean {
    return this.expandedVersions.has(versionIndex);
  }

  protected filterAiGeneratedTag(tags: string[] | null): string[] {
    if (!tags) return [];
    return tags.filter(tag => {
      const normalized = tag.toLowerCase().replace(/\s+/g, '_');
      return normalized !== 'ai_generated';
    });
  }

  protected filterInstalledCapability(capabilities: string[] | null): string[] {
    if (!capabilities) return [];
    return capabilities.filter(cap => cap !== 'Installed');
  }

  protected hasNonInstalledCapabilities(capabilities: string[] | null): boolean {
    if (!capabilities) return false;
    return capabilities.some(cap => cap !== 'Installed');
  }

  protected setActiveTab(tab: 'main' | 'other'): void {
    this.activeTab.set(tab);
  }

  protected getTotalVersionsCount(): number {
    return this.otherEvolvumVersions().length + this.otherCommunityVersions().length;
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

  private loadApplication(id: string): void {
    this.applicationService.getById(id).subscribe({
      next: (data) => {
        this.application.set(data);
        this.groupVersionsByLifecycleState(data.implementationVersions);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set('Failed to load application details');
        this.loading.set(false);
        console.error('Error loading application:', err);
      }
    });
  }

  private groupVersionsByLifecycleState(versions: any[] | null): void {
    if (!versions) {
      this.activeEvolvumVersions.set([]);
      this.activeCommunityVersions.set([]);
      this.otherEvolvumVersions.set([]);
      this.otherCommunityVersions.set([]);
      return;
    }

    const activeEvolveum: any[] = [];
    const activeCommunity: any[] = [];
    const otherEvolveum: any[] = [];
    const otherCommunity: any[] = [];

    versions.forEach(version => {
      const isEvolveum = version.author && version.author.toLowerCase().includes('evolveum');
      const isActive = version.lifecycleState === 'ACTIVE';

      if (isActive && isEvolveum) {
        activeEvolveum.push(version);
      } else if (isActive && !isEvolveum) {
        activeCommunity.push(version);
      } else if (!isActive && isEvolveum) {
        otherEvolveum.push(version);
      } else {
        otherCommunity.push(version);
      }
    });

    this.activeEvolvumVersions.set(activeEvolveum);
    this.activeCommunityVersions.set(activeCommunity);
    this.otherEvolvumVersions.set(otherEvolveum);
    this.otherCommunityVersions.set(otherCommunity);
  }
}
