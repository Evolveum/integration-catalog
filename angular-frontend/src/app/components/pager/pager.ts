/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, input, output } from '@angular/core';

/** Previous / page numbers / next, in the look of the applications list pager; hidden for a single page. */
@Component({
  selector: 'app-pager',
  standalone: true,
  templateUrl: './pager.html',
  styleUrls: ['./pager.scss']
})
export class Pager {
  /** Zero-based. */
  readonly page = input.required<number>();
  readonly totalPages = input.required<number>();
  readonly pageChange = output<number>();

  protected goTo(page: number): void {
    if (page < 0 || page >= this.totalPages() || page === this.page()) return;
    this.pageChange.emit(page);
  }
}
