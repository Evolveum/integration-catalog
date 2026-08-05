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
  host: { style: 'display: block; position: sticky; top: 0; z-index: 1000;' }
})
export class PageHeader {
  @Input() breadcrumb: boolean = false;
  @Input() hideBorder: boolean = false;

  protected readonly authService = inject(AuthService);
  protected readonly toastService = inject(ToastService);

  protected readonly currentUser = this.authService.currentUser;

  protected login(): void { this.authService.login(); }
  protected logout(): void { this.authService.logout(); }

  protected closeToast(): void {
    this.toastService.close();
  }
}
