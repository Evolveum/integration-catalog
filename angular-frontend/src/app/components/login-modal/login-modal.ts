/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, signal, Output, EventEmitter, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-login-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './login-modal.html',
  styleUrls: ['./login-modal.css']
})
export class LoginModal {
  @Input() isOpen = signal<boolean>(false);
  @Output() modalClosed = new EventEmitter<void>();

  protected username = '';
  protected password = '';
  protected readonly errorMessage = signal<string | null>(null);

  constructor(private authService: AuthService) {}

  protected closeModal(): void {
    this.modalClosed.emit();
    this.resetForm();
  }

  protected login(): void {
    this.errorMessage.set(null);

    if (!this.username || !this.password) {
      this.errorMessage.set('Please enter both username and password');
      return;
    }

    const success = this.authService.login(this.username, this.password);

    if (success) {
      this.closeModal();
    } else {
      this.errorMessage.set('Invalid username or password');
    }
  }

  private resetForm(): void {
    this.username = '';
    this.password = '';
    this.errorMessage.set(null);
  }
}
