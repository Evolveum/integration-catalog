/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import {Component, inject, Input, signal} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { LoginModal } from '../login-modal/login-modal';
import { ToastService } from '../../services/toast.service';

@Component({
  selector: 'app-page-header',
  standalone: true,
  imports: [CommonModule, RouterLink, LoginModal],
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
  protected readonly loginModalOpen = this.authService.loginModalOpen;

  protected openLoginModal(): void { this.authService.openLoginModal(); }
  protected closeLoginModal(): void { this.authService.closeLoginModal(); }
  protected logout(): void { this.authService.logout(); }

  protected closeToast(): void {
    this.toastService.close();
  }
}
