/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, inject } from '@angular/core';
import { AuthService } from '../../services/auth.service';

/** Tells a tab that still showed a logged-in user that the session has ended, and offers a login. */
@Component({
  selector: 'app-session-expired-modal',
  standalone: true,
  templateUrl: './session-expired-modal.html',
  styleUrls: ['./session-expired-modal.scss']
})
export class SessionExpiredModal {
  private readonly authService = inject(AuthService);

  protected onLogin(): void {
    this.authService.login();
  }

  protected onClose(): void {
    this.authService.dismissSessionLost();
  }
}
