/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { inject } from '@angular/core';
import { HttpErrorResponse, HttpInterceptorFn, HttpStatusCode } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';
import { environment } from '../../environments/environment';
import { AuthService } from './auth.service';

/**
 * Re-checks the session when an API call is refused, so a tab whose session ended elsewhere stops
 * showing the user as logged in. A 403 counts too: logging out drops the XSRF cookie, so a lost
 * session usually surfaces as a CSRF refusal before it can become a 401.
 */
export const sessionExpiryInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse
          && (error.status === HttpStatusCode.Unauthorized || error.status === HttpStatusCode.Forbidden)
          && req.url.startsWith(environment.apiUrl)
          && !req.url.endsWith('/auth/me')
          && authService.isLoggedIn()) {
        authService.verifySession().subscribe();
      }
      return throwError(() => error);
    })
  );
};
