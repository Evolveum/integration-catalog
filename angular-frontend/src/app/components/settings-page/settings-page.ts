/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, computed, inject, signal } from '@angular/core';
import { CommonModule, Location } from '@angular/common';
import { Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { PageHeader } from '../page-header/page-header';
import { ApiKeysPanel } from '../api-keys-panel/api-keys-panel';

type SettingsTab = 'profile' | 'api-keys';

type ProfileTab = 'general' | 'contact' | 'regional';

/**
 * Account settings, reached from the account menu. The profile is read-only: the identity
 * provider owns that data.
 */
@Component({
  selector: 'app-settings-page',
  standalone: true,
  imports: [CommonModule, PageHeader, ApiKeysPanel],
  templateUrl: './settings-page.html',
  styleUrls: ['./settings-page.scss']
})
export class SettingsPage {
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  protected readonly authService = inject(AuthService);

  protected readonly profile = this.authService.profile;
  protected readonly activeTab = signal<SettingsTab>('profile');
  protected readonly activeProfileTab = signal<ProfileTab>('general');

  /** Name on the profile card: the provider's full name, or the username without one. */
  protected readonly displayName = computed(() =>
    this.profile()?.fullName?.trim() || this.profile()?.username || '');

  protected readonly initials = this.authService.initials;

  /** Returns where the user came from, or to the catalog when opened directly. */
  protected goBack(): void {
    if (window.history.length > 1) {
      this.location.back();
    } else {
      this.router.navigate(['/applications']);
    }
  }
}
