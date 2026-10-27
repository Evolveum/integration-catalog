/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, inject } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { PageHeader } from '../page-header/page-header';

interface ErrorText {
  title: string;
  description: string;
}

const ERROR_TEXTS: Record<number, ErrorText> = {
  403: {
    title: 'Access denied',
    description: 'You do not have permission to view this page.'
  },
  404: {
    title: 'Page not found',
    description: 'The page you are looking for does not exist or has been removed.'
  },
  500: {
    title: 'Something went wrong',
    description: 'An unexpected error occurred on our side. Please try again later.'
  }
};

const FALLBACK_TEXT: ErrorText = {
  title: 'Unexpected error',
  description: 'The request could not be completed. Please try again later.'
};

/**
 * Error page for failed page loads. Reached three ways: Spring redirects browser errors to
 * /error-page/{status}?path=..., pages navigate here when their main data fails to load, and the
 * wildcard route renders it in place for unknown client-side URLs (no status param = 404).
 */
@Component({
  selector: 'app-error-page',
  standalone: true,
  imports: [RouterLink, PageHeader],
  templateUrl: './error-page.html',
  styleUrls: ['./error-page.scss']
})
export class ErrorPage {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly status = this.resolveStatus();
  protected readonly text = ERROR_TEXTS[this.status] ?? (this.status >= 500 ? ERROR_TEXTS[500] : FALLBACK_TEXT);
  protected readonly path = this.route.snapshot.queryParamMap.get('path')
    ?? (this.route.snapshot.paramMap.has('status') ? null : this.router.url);

  private resolveStatus(): number {
    const status = Number(this.route.snapshot.paramMap.get('status'));
    return Number.isInteger(status) && status >= 400 && status < 600 ? status : 404;
  }
}
