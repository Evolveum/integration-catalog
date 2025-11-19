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

    // Get current user from localStorage
    const currentUser = localStorage.getItem('currentUser');

    // Clone request and add headers
    const headers: { [key: string]: string } = {};
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    if (currentUser) {
      headers['X-User-Name'] = currentUser;
    }

    const authReq = Object.keys(headers).length > 0
      ? req.clone({ setHeaders: headers })
      : req;
    return next(authReq);
  }

  // For external APIs, pass through without modification
  return next(req);
};
