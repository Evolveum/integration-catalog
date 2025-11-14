/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { HttpInterceptorFn } from '@angular/common/http';

// inteceptor used for tokens/cookies

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  // attach bearer token if present
  const token = localStorage.getItem('access_token');
  const authReq = token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;
  return next(authReq);
};
