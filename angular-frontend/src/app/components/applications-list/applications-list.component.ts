import { Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApplicationService } from '../../services/application.service';
import { Application } from '../../models/Application';
import { ApplicationCardComponent } from '../../components/application-card/application-card.component';


@Component({
  selector: 'app-applications-list',
  standalone: true,
  imports: [CommonModule, ApplicationCardComponent],
  templateUrl: './applications-list.component.html',
  styleUrls: ['./applications-list.component.scss']
})
export class ApplicationsListComponent implements OnInit {
  private svc = inject(ApplicationService);

  apps: Application[] = [];
  loading = true;
  error: string | null = null;

  ngOnInit(): void {
    this.svc.getAll().subscribe({
      next: data => { this.apps = data; this.loading = false; },
      error: err => { this.error = 'Failed to load applications.' + err; this.loading = false; }
    });
  }
}
