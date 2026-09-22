/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, EventEmitter, Input, OnInit, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApplicationService } from '../../services/application.service';
import { OverflowTitleDirective } from '../../directives/overflow-title.directive';

/**
 * Picks the types of an integration method after it exists, with the cards the publish flow uses for
 * the same choice. Works in display names, as the publish flow does - the revision carries its types
 * by name, and the caller turns them back into ids when it saves.
 *
 * <p>Editing is deliberately all-or-nothing: the selection is applied on Save and thrown away on
 * Cancel, so a half-made change never reaches the form behind it.
 */
@Component({
  selector: 'app-method-type-modal',
  standalone: true,
  imports: [CommonModule, OverflowTitleDirective],
  templateUrl: './method-type-modal.html',
  styleUrls: ['./method-type-modal.scss']
})
export class MethodTypeModal implements OnInit {
  /** The types the method has now, by display name. */
  @Input() selected: string[] = [];

  @Output() save = new EventEmitter<string[]>();
  @Output() cancel = new EventEmitter<void>();

  protected readonly types = signal<{ id: number; displayName: string; description: string | null }[]>([]);
  protected readonly isLoading = signal<boolean>(true);
  protected readonly loadError = signal<string>('');
  /** The working copy; the caller's list is untouched until Save. */
  protected readonly picked = signal<string[]>([]);

  constructor(private applicationService: ApplicationService) {}

  ngOnInit(): void {
    this.picked.set([...this.selected]);
    this.applicationService.getIntegrationMethodTypes().subscribe({
      next: (data) => {
        this.types.set(data);
        this.isLoading.set(false);
      },
      error: () => {
        this.loadError.set('The integration method types could not be loaded. Please try again.');
        this.isLoading.set(false);
      }
    });
  }

  protected isPicked(displayName: string): boolean {
    return this.picked().includes(displayName);
  }

  protected toggle(displayName: string): void {
    const current = this.picked();
    this.picked.set(current.includes(displayName)
      ? current.filter(v => v !== displayName)
      : [...current, displayName]);
  }

  /** At least one type, the same rule the publish flow's Continue button enforces. */
  protected get canSave(): boolean {
    return this.picked().length > 0;
  }

  protected onSave(): void {
    if (this.canSave) {
      this.save.emit(this.picked());
    }
  }

  protected onCancel(): void {
    this.cancel.emit();
  }
}
