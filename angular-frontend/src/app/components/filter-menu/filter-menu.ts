/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { afterNextRender, Component, computed, ElementRef, input, output, signal, viewChild } from '@angular/core';

export interface FilterOption {
  value: string;
  label: string;
}

/** Above this many options the menu gets a search field; the list itself scrolls past this many rows. */
const SEARCH_THRESHOLD = 7;

/**
 * The checkbox dropdown of a self-service filter chip: search field on top for long lists,
 * a scrollable list of options and an always visible "Clear filter" footer.
 */
@Component({
  selector: 'app-filter-menu',
  standalone: true,
  templateUrl: './filter-menu.html',
  styleUrls: ['./filter-menu.scss']
})
export class FilterMenu {
  readonly options = input.required<FilterOption[]>();
  readonly selected = input.required<ReadonlySet<string>>();
  readonly toggle = output<string>();
  readonly clear = output<void>();

  private readonly searchInput = viewChild<ElementRef<HTMLInputElement>>('searchInput');

  protected readonly query = signal('');
  protected readonly searchable = computed(() => this.options().length > SEARCH_THRESHOLD);
  protected readonly visibleOptions = computed(() => {
    const query = this.query().trim().toLowerCase();
    return query ? this.options().filter(o => o.label.toLowerCase().includes(query)) : this.options();
  });

  constructor() {
    afterNextRender(() => this.searchInput()?.nativeElement.focus());
  }
}
