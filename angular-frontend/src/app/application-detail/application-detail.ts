import { Component, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { ApplicationService } from '../services/application.service';
import { ApplicationDetail as ApplicationDetailModel } from '../models/application-detail.model';

@Component({
  selector: 'app-application-detail',
  imports: [CommonModule],
  templateUrl: './application-detail.html',
  styleUrl: './application-detail.css'
})
export class ApplicationDetail implements OnInit {
  protected application = signal<ApplicationDetailModel | null>(null);
  protected loading = signal<boolean>(true);
  protected error = signal<string | null>(null);

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private applicationService: ApplicationService
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.loadApplication(id);
    } else {
      this.error.set('No application ID provided');
      this.loading.set(false);
    }
  }

  protected goBack(): void {
    this.router.navigate(['/applications']);
  }

  private loadApplication(id: string): void {
    this.applicationService.getById(id).subscribe({
      next: (data) => {
        this.application.set(data);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set('Failed to load application details');
        this.loading.set(false);
        console.error('Error loading application:', err);
      }
    });
  }
}
