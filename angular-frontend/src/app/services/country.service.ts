/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { map, shareReplay, catchError } from 'rxjs/operators';

export interface Country {
  name: string;
  code: string;
}

@Injectable({
  providedIn: 'root'
})
export class CountryService {
  // Request only the fields we need to reduce response size and avoid errors
  private readonly apiUrl = 'https://restcountries.com/v3.1/all?fields=name,cca2';
  private countries$: Observable<Country[]> | null = null;

  constructor(private http: HttpClient) {}

  /**
   * Fetches all countries from REST Countries API
   * Results are cached to avoid repeated API calls
   */
  getAllCountries(): Observable<Country[]> {
    if (!this.countries$) {
      this.countries$ = this.http.get<any[]>(this.apiUrl).pipe(
        map(countries =>
          countries
            .map(country => ({
              name: country.name.common,
              code: country.cca2
            }))
            .sort((a, b) => a.name.localeCompare(b.name))
        ),
        catchError(error => {
          console.error('Error fetching countries from REST API:', error);
          return of([]); // Return empty array on error
        }),
        shareReplay(1) // Cache the result
      );
    }
    return this.countries$;
  }
}
