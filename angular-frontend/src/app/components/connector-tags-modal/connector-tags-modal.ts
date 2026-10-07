/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, EventEmitter, Input, OnInit, Output, signal } from '@angular/core';
import { SUPPORT_TIERS, SupportTier } from '../../core/support-tier';

/** What the modal's Save sends. */
export interface ConnectorTagsChange {
  tier: SupportTier | null;
  obsolete: boolean;
}

/**
 * Edits a connector's superuser-set tags from the all-connectors page: its support tier and the
 * obsolete tag. The parent renders it inside an @if, saves on `save` and passes the processing/error
 * state back.
 */
@Component({
  selector: 'app-connector-tags-modal',
  standalone: true,
  templateUrl: './connector-tags-modal.html',
  styleUrls: ['./connector-tags-modal.scss']
})
export class ConnectorTagsModal implements OnInit {
  @Input() connectorName: string | null = null;
  @Input() tier: SupportTier | null = null;
  @Input() obsolete = false;
  @Input() processing = false;
  @Input() error = '';
  @Output() save = new EventEmitter<ConnectorTagsChange>();
  @Output() close = new EventEmitter<void>();

  protected readonly supportTiers = SUPPORT_TIERS;
  protected readonly selectedTier = signal<SupportTier | null>(null);
  protected readonly selectedObsolete = signal(false);

  ngOnInit(): void {
    this.selectedTier.set(this.tier);
    this.selectedObsolete.set(this.obsolete);
  }

  protected isChanged(): boolean {
    return this.selectedTier() !== this.tier || this.selectedObsolete() !== this.obsolete;
  }

  protected onObsoleteChange(event: Event): void {
    this.selectedObsolete.set((event.target as HTMLInputElement).checked);
  }

  protected onTierChange(event: Event): void {
    this.selectedTier.set(((event.target as HTMLSelectElement).value || null) as SupportTier | null);
  }

  protected onSave(): void {
    if (this.processing) return;
    this.save.emit({ tier: this.selectedTier(), obsolete: this.selectedObsolete() });
  }

  protected onClose(): void {
    if (this.processing) return;
    this.close.emit();
  }
}
