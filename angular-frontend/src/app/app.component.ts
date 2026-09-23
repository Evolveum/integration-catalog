/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, HostListener, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { AuthService } from './services/auth.service';
import { SessionExpiredModal } from './components/session-expired-modal/session-expired-modal';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, SessionExpiredModal],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss']
})
export class AppComponent {
  title = 'ic-frontend';

  protected readonly authService = inject(AuthService);

  /** Coming back to a tab re-checks the session, which may have ended in another tab meanwhile. */
  @HostListener('document:visibilitychange')
  protected onVisibilityChange(): void {
    if (document.visibilityState === 'visible' && this.authService.isLoggedIn()) {
      this.authService.verifySession().subscribe();
    }
  }
}
