/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, HostListener, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRouteSnapshot, NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { AuthService } from './services/auth.service';
import { SessionExpiredModal } from './components/session-expired-modal/session-expired-modal';
import { PageFooter } from './components/page-footer/page-footer';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, SessionExpiredModal, PageFooter],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss']
})
export class AppComponent {
  title = 'ic-frontend';

  protected readonly authService = inject(AuthService);

  private readonly router = inject(Router);

  /** Hidden on routes flagged with data.hideFooter (see app.routes.ts). */
  protected readonly showFooter = toSignal(
    this.router.events.pipe(
      filter(event => event instanceof NavigationEnd),
      map(() => !this.deepestChild(this.router.routerState.snapshot.root).data['hideFooter'])
    ),
    { initialValue: false }
  );

  private deepestChild(route: ActivatedRouteSnapshot): ActivatedRouteSnapshot {
    return route.firstChild ? this.deepestChild(route.firstChild) : route;
  }

  /** Coming back to a tab re-checks the session, which may have ended in another tab meanwhile. */
  @HostListener('document:visibilitychange')
  protected onVisibilityChange(): void {
    if (document.visibilityState === 'visible' && this.authService.isLoggedIn()) {
      this.authService.verifySession().subscribe();
    }
  }
}
