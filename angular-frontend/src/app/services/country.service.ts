/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { shareReplay, catchError } from 'rxjs/operators';

export interface Country {
  name: string;
  code: string;
}

@Injectable({
  providedIn: 'root'
})
export class CountryService {
  private countries$: Observable<Country[]> | null = null;

  constructor(private http: HttpClient) {}

  /**
   * Fetches all countries from local JSON file
   * Results are cached to avoid repeated file reads
   */
  getAllCountries(): Observable<Country[]> {
    if (!this.countries$) {
      this.countries$ = this.http.get<Country[]>('/assets/countries.json').pipe(
        catchError(error => {
          console.error('Error loading countries from JSON file:', error);
          return of([]); // Return empty array on error
        }),
        shareReplay(1) // Cache the result
      );
    }
    return this.countries$;
  }
}
