/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';

/**
 * Replaces the current page with the error page, keeping the failed address so it can be shown.
 * Without an HTTP status (network failure, missing record in a loaded list) it reports 404 for a
 * missing record and 500 otherwise.
 */
export function showErrorPage(router: Router, error?: unknown): void {
  const status = error instanceof HttpErrorResponse && error.status >= 400 ? error.status
    : error === undefined ? 404 : 500;
  router.navigate(['/error-page', status], { queryParams: { path: router.url }, replaceUrl: true });
}
