/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Injectable } from '@angular/core';
import { FilterState } from '../components/filter-modal/filter-modal';

/**
 * Snapshot of the applications list view state, kept alive across navigation
 * (e.g. opening an app's detail and coming back) so filters/search/paging are
 * restored instead of reset. Lives for the app session; the list's "Reset
 * filter" clears the filters themselves.
 */
export interface ApplicationsListState {
  filterState: FilterState;
  visibleChips: string[];
  searchQuery: string;
  currentPage: number;
  sortBy: 'alphabetical' | 'popularity' | 'activity';
  activeTab: string;
}

@Injectable({ providedIn: 'root' })
export class ApplicationsListStateService {
  private state: ApplicationsListState | null = null;

  save(state: ApplicationsListState): void {
    this.state = state;
  }

  restore(): ApplicationsListState | null {
    return this.state;
  }

  clear(): void {
    this.state = null;
  }
}
