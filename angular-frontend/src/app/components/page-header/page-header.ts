/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import {Component, computed, inject, Input, signal} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService, UserRole } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { StagingBanner } from '../staging-banner/staging-banner';

@Component({
  selector: 'app-page-header',
  standalone: true,
  imports: [CommonModule, RouterLink, StagingBanner],
  templateUrl: './page-header.html',
  styleUrls: ['./page-header.scss'],
  host: {
    style: 'display: block; position: sticky; top: 0; z-index: 1000;',
    '(document:keydown.escape)': 'closeMenu()',
    '(window:scroll)': 'onWindowScroll()'
  }
})
export class PageHeader {
  @Input() breadcrumb: boolean = false;
  @Input() hideBorder: boolean = false;
  /**
   * Lets the page's hero gradient show through the header while the page is scrolled to the top,
   * so the two read as one surface; the header turns solid as soon as the user scrolls.
   */
  @Input() transparentAtTop: boolean = false;
  /** Opt-in for the unregistered-organization warning, shown only where the user publishes. */
  @Input() showUnregisteredOrgWarning: boolean = false;

  protected readonly authService = inject(AuthService);
  protected readonly toastService = inject(ToastService);

  protected readonly currentUser = this.authService.currentUser;

  protected readonly menuOpen = signal(false);

  protected readonly scrolledToTop = signal(window.scrollY <= 0);

  protected readonly userInitials = this.authService.initials;

  protected readonly isSuperuser = computed(() => this.authService.currentRole() === UserRole.Superuser);

  protected onWindowScroll(): void { this.scrolledToTop.set(window.scrollY <= 0); }

  protected toggleMenu(): void { this.menuOpen.update(open => !open); }
  protected closeMenu(): void { this.menuOpen.set(false); }

  protected login(): void { this.authService.login(); }

  protected logout(): void {
    this.closeMenu();
    this.authService.logout();
  }

  protected closeToast(): void {
    this.toastService.close();
  }
}
