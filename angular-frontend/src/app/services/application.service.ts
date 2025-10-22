import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Application } from '../models/application.model';
import { ApplicationDetail } from '../models/application-detail.model';
import { CategoryCount } from '../models/category-count.model';
import { PendingRequest } from '../models/pending-request.model';

@Injectable({
  providedIn: 'root'
})
export class ApplicationService {
  private apiUrl = 'http://localhost:8080/api/applications';

  constructor(private http: HttpClient) {}

  getAll(): Observable<Application[]> {
    return this.http.get<Application[]>(this.apiUrl);
  }

  getById(id: string): Observable<ApplicationDetail> {
    return this.http.get<ApplicationDetail>(`http://localhost:8080/api/application/${id}`);
  }

  getCategoryCounts(): Observable<CategoryCount[]> {
    return this.http.get<CategoryCount[]>('http://localhost:8080/api/categories/counts');
  }

  getCommonTagCounts(): Observable<CategoryCount[]> {
    return this.http.get<CategoryCount[]>('http://localhost:8080/api/common-tags/counts');
  }

  getAppStatusCounts(): Observable<CategoryCount[]> {
    return this.http.get<CategoryCount[]>('http://localhost:8080/api/app-status/counts');
  }

  getSupportedOperationsCounts(): Observable<CategoryCount[]> {
    return this.http.get<CategoryCount[]>('http://localhost:8080/api/supported-operations/counts');
  }

  submitPendingRequest(request: PendingRequest): Observable<any> {
    return this.http.post<any>('http://localhost:8080/api/pending-request', request);
  }

  getPendingRequests(): Observable<Application[]> {
    return this.http.get<Application[]>('http://localhost:8080/api/pending-requests');
  }
}
