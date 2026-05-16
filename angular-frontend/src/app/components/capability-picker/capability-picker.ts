/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, Output, EventEmitter, signal, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApplicationService } from '../../services/application.service';

export interface CapabilityGroup {
  objectClass: string;
  capabilityNames: string[];
}

@Component({
  selector: 'app-capability-picker',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './capability-picker.html',
  styleUrls: ['./capability-picker.scss']
})
export class CapabilityPicker implements OnInit {
  @Output() capabilitiesChange = new EventEmitter<CapabilityGroup[]>();

  protected readonly isLoading        = signal<boolean>(false);
  protected readonly availableCaps    = signal<string[]>([]);
  protected readonly scope            = signal<'global' | 'specific'>('global');
  protected readonly globalCaps       = signal<string[]>([]);
  protected readonly isGlobalOpen     = signal<boolean>(false);
  protected readonly entries          = signal<{ objectClass: string; capabilities: string[]; isOpen: boolean }[]>(
    [{ objectClass: '', capabilities: [], isOpen: false }]
  );

  constructor(private applicationService: ApplicationService) {}

  ngOnInit(): void {
    this.isLoading.set(true);
    this.applicationService.getCapabilities().subscribe({
      next: (caps) => { this.availableCaps.set(caps); this.isLoading.set(false); },
      error: () => this.isLoading.set(false)
    });
  }

  public reset(): void {
    this.scope.set('global');
    this.globalCaps.set([]);
    this.isGlobalOpen.set(false);
    this.entries.set([{ objectClass: '', capabilities: [], isOpen: false }]);
    this.capabilitiesChange.emit([]);
  }

  private emit(): void {
    if (this.scope() === 'global') {
      const caps = this.globalCaps();
      this.capabilitiesChange.emit(caps.length > 0 ? [{ objectClass: 'Global', capabilityNames: caps }] : []);
    } else {
      this.capabilitiesChange.emit(
        this.entries()
          .filter(e => e.objectClass && e.capabilities.length > 0)
          .map(e => ({ objectClass: e.objectClass, capabilityNames: e.capabilities }))
      );
    }
  }

  protected setScope(s: 'global' | 'specific'): void {
    this.scope.set(s);
    this.emit();
  }

  protected fmt(cap: string): string {
    return cap.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
  }

  // Global caps
  protected onGlobalChange(event: Event, cap: string): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.globalCaps.update(cs => checked ? [...cs, cap] : cs.filter(c => c !== cap));
    this.emit();
  }

  protected removeGlobal(cap: string, event: Event): void {
    event.stopPropagation();
    this.globalCaps.update(cs => cs.filter(c => c !== cap));
    this.emit();
  }

  // Object class entries
  protected addEntry(): void {
    this.entries.update(es => [...es, { objectClass: '', capabilities: [], isOpen: false }]);
  }

  protected removeEntry(i: number): void {
    this.entries.update(es => es.filter((_, idx) => idx !== i));
    this.emit();
  }

  protected updateObjectClass(i: number, value: string): void {
    this.entries.update(es => es.map((e, idx) => idx !== i ? e : {
      ...e, objectClass: value, isOpen: value ? e.isOpen : false
    }));
    this.emit();
  }

  protected toggleEntryDropdown(i: number): void {
    if (!this.entries()[i]?.objectClass) return;
    this.entries.update(es => es.map((e, idx) =>
      idx === i ? { ...e, isOpen: !e.isOpen } : e
    ));
  }

  protected onSpecificChange(event: Event, i: number, cap: string): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.entries.update(es => es.map((e, idx) => {
      if (idx !== i) return e;
      const capabilities = checked ? [...e.capabilities, cap] : e.capabilities.filter(c => c !== cap);
      return { ...e, capabilities };
    }));
    this.emit();
  }

  protected removeSpecific(i: number, cap: string, event: Event): void {
    event.stopPropagation();
    this.entries.update(es => es.map((e, idx) =>
      idx !== i ? e : { ...e, capabilities: e.capabilities.filter(c => c !== cap) }
    ));
    this.emit();
  }
}
