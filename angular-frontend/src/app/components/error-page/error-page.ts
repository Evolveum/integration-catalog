/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { PageHeader } from '../page-header/page-header';
import { LinksService } from '../../services/links.service';
import { NavigationHistoryService } from '../../services/navigation-history.service';

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

/** One part of the issue text; an empty line is a gap for the user to fill. */
interface IssueSection {
  heading: string;
  lines: string[];
  list?: 'ol' | 'ul';
}

function toPlainText(sections: IssueSection[]): string {
  return sections.map(section => [
    `## ${section.heading}`,
    ...section.lines.map((line, i) =>
      section.list === 'ol' ? `${i + 1}. ${line}` : section.list === 'ul' ? `- ${line}` : line)
  ].join('\n')).join('\n\n');
}

function toHtml(sections: IssueSection[]): string {
  // A non-breaking space keeps empty items and paragraphs from being dropped on paste.
  const cell = (line: string) => line ? escapeHtml(line) : '&nbsp;';
  return sections.map(section => {
    const body = section.list
      ? `<${section.list}>${section.lines.map(line => `<li>${cell(line)}</li>`).join('')}</${section.list}>`
      : section.lines.map(line => `<p>${cell(line)}</p>`).join('');
    return `<h2>${escapeHtml(section.heading)}</h2>${body}`;
  }).join('');
}

/** Local time with the UTC offset, so support can line it up with server logs. */
function formatTime(value: string | Date): string {
  return new DatePipe('en-US').transform(value, 'MMM d, y, HH:mm:ss (zzzz)') ?? String(value);
}

function escapeHtml(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/**
 * Error page for failed page loads. Reached three ways: Spring redirects browser errors to
 * /error-page/{status}?path=..., pages navigate here when their main data fails to load, and the
 * wildcard route renders it in place for unknown client-side URLs (no status param = 404).
 * Except for 401, it offers reporting the problem on the support portal: the portal prefills only
 * project and type, so an issue template with the error details is copied for the user to paste.
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
  private readonly navigationHistory = inject(NavigationHistoryService);

  protected readonly links = inject(LinksService).links;

  protected readonly status = this.resolveStatus();
  protected readonly text = ERROR_TEXTS[this.status] ?? (this.status >= 500 ? ERROR_TEXTS[500] : FALLBACK_TEXT);
  protected readonly path = this.route.snapshot.queryParamMap.get('path')
    ?? (this.route.snapshot.paramMap.has('status') ? null : this.router.url);
  protected readonly reportable = this.status !== 401;
  protected readonly reportOpen = signal(false);
  protected readonly copied = signal(false);
  private readonly issue = this.buildIssue();
  protected readonly details = toPlainText(this.issue);

  /**
   * The portal cannot prefill the description from the URL, so the issue text is copied first and
   * the portal opened after, while this page still has focus for the clipboard. The portal opens
   * even when copying fails; the text stays on the page to copy by hand.
   */
  protected async reportIssue(event: MouseEvent, url: string): Promise<void> {
    event.preventDefault();
    this.copied.set(await this.copyDetails().then(() => true, () => false));
    window.open(url, '_blank', 'noopener,noreferrer');
  }

  /**
   * Copies both flavours: rich editors (the portal's description field) paste the HTML as real
   * headings and lists, anything else gets the plain-text Markdown.
   */
  private copyDetails(): Promise<void> {
    return typeof ClipboardItem === 'undefined'
      ? navigator.clipboard.writeText(this.details)
      : navigator.clipboard.write([new ClipboardItem({
        'text/plain': new Blob([this.details], { type: 'text/plain' }),
        'text/html': new Blob([toHtml(this.issue)], { type: 'text/html' })
      })]);
  }

  /** Sections for the user to fill, then the collected details. */
  private buildIssue(): IssueSection[] {
    const visited = this.navigationHistory.recent()
      .filter(page => !page.url.startsWith('/error-page'))
      .map(page => `${formatTime(page.at)}: ${page.url}`);
    return [
      { heading: 'What were you trying to do', lines: [''] },
      { heading: 'Steps to reproduce', lines: ['', '', ''], list: 'ol' },
      { heading: 'Expected result', lines: [''] },
      { heading: 'Actual result', lines: [`Error ${this.status}: ${this.text.title}`] },
      {
        heading: 'Error details',
        lines: [
          `Address: ${this.path ?? this.router.url}`,
          `Time: ${formatTime(new Date())}`,
          `Browser: ${navigator.userAgent}`
        ],
        list: 'ul'
      },
      { heading: 'Recently visited pages (oldest first)', lines: visited.length ? visited : ['none recorded'], list: 'ul' }
    ];
  }

  private resolveStatus(): number {
    const status = Number(this.route.snapshot.paramMap.get('status'));
    return Number.isInteger(status) && status >= 400 && status < 600 ? status : 404;
  }
}
