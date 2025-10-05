import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Application } from '../models/Application';
import { environment } from '../environments/environment';

@Injectable({ providedIn: 'root' })
export class ApplicationService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/applications`;

  getAll() {
    return this.http.get<Application[]>(this.baseUrl);
  }
}
