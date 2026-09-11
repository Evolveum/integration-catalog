/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import {Component, inject, Input, signal} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../services/auth.service';
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
    '(document:keydown.escape)': 'closeMenu()'
  }
})
export class PageHeader {
  @Input() breadcrumb: boolean = false;
  @Input() hideBorder: boolean = false;

  protected readonly authService = inject(AuthService);
  protected readonly toastService = inject(ToastService);

  protected readonly currentUser = this.authService.currentUser;

  protected readonly menuOpen = signal(false);

  protected readonly userInitials = this.authService.initials;

  protected toggleMenu(): void { this.menuOpen.update(open => !open); }
  protected closeMenu(): void { this.menuOpen.set(false); }

  protected login(): void { this.authService.login(); }

  protected logout(): void {
    this.closeMenu();
    this.authService.logout();
  }

  /**
   * Placeholder for menu entries whose pages do not exist yet, so they give feedback instead of
   * doing nothing. Replace with routing once the pages land.
   *
   * @param label the menu entry the user picked
   */
  protected openComingSoon(label: string): void {
    this.closeMenu();
    this.toastService.show(label, 'This page is not available yet.', 'info');
  }

  protected closeToast(): void {
    this.toastService.close();
  }
}
