/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { HttpInterceptorFn } from '@angular/common/http';
import { environment } from '../../../environments/environment';

// inteceptor used for tokens/cookies

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  // Only add auth header to requests going to our backend API
  // Skip external APIs (like REST Countries API)
  const isBackendRequest = req.url.startsWith(environment.apiUrl) ||
                          req.url.startsWith('/api') ||
                          !req.url.startsWith('http');

  if (isBackendRequest) {
    // attach bearer token if present
    const token = localStorage.getItem('access_token');
    const authReq = token
      ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : req;
    return next(authReq);
  }

  // For external APIs, pass through without modification
  return next(req);
};
