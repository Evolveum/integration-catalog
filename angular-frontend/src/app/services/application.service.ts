import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Application } from '../models/application.model';
import { ApplicationDetail } from '../models/application-detail.model';
import { CategoryCount } from '../models/category-count.model';

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

  submitVote(requestId: number, voter: string): Observable<any> {
    return this.http.post<any>(`http://localhost:8080/api/requests/${requestId}/vote?voter=${voter}`, {});
  }

  getVoteCount(requestId: number): Observable<number> {
    return this.http.get<number>(`http://localhost:8080/api/requests/${requestId}/votes/count`);
  }

  hasUserVoted(requestId: number, voter: string): Observable<boolean> {
    return this.http.get<boolean>(`http://localhost:8080/api/requests/${requestId}/votes/check?voter=${voter}`);
  }

  submitRequest(request: any): Observable<any> {
    return this.http.post<any>('http://localhost:8080/api/requests', request);
  }
}
