import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Application } from '../../models/Application';

@Component({
  selector: 'app-application-card',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './application-card.component.html',
  styleUrls: ['./application-card.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ApplicationCardComponent {
  @Input({ required: true }) app!: Application;
}
