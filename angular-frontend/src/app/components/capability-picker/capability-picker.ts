/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, Output, EventEmitter, signal, OnInit, HostListener, ElementRef } from '@angular/core';
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

  protected readonly isLoading         = signal<boolean>(false);
  protected readonly globalAvailable   = signal<string[]>([]);
  protected readonly specificAvailable = signal<string[]>([]);
  protected readonly globalCaps        = signal<string[]>([]);
  protected readonly isGlobalOpen      = signal<boolean>(false);
  protected readonly entries           = signal<{ objectClass: string; capabilities: string[]; isOpen: boolean }[]>(
    [{ objectClass: '', capabilities: [], isOpen: false }]
  );

  constructor(private applicationService: ApplicationService, private el: ElementRef) {}

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.el.nativeElement.contains(event.target as Node)) {
      this.isGlobalOpen.set(false);
      this.entries.update(es => es.map(e => ({ ...e, isOpen: false })));
    }
  }

  ngOnInit(): void {
    this.isLoading.set(true);
    this.applicationService.getCapabilities().subscribe({
      next: (caps) => {
        const byOrder = (a: { displayOrder: number | null }, b: { displayOrder: number | null }) =>
          (a.displayOrder ?? 0) - (b.displayOrder ?? 0);
        this.globalAvailable.set(caps.filter(c => c.globality === 'GLOBAL').sort(byOrder).map(c => c.name));
        this.specificAvailable.set(caps.filter(c => c.globality === 'SPECIFIC').sort(byOrder).map(c => c.name));
        this.isLoading.set(false);
      },
      error: () => this.isLoading.set(false)
    });
  }

  public reset(): void {
    this.globalCaps.set([]);
    this.isGlobalOpen.set(false);
    this.entries.set([{ objectClass: '', capabilities: [], isOpen: false }]);
    this.capabilitiesChange.emit([]);
  }

  private emit(): void {
    const groups: CapabilityGroup[] = [];
    const global = this.globalCaps();
    if (global.length > 0) {
      groups.push({ objectClass: 'Global', capabilityNames: global });
    }
    this.entries()
      .filter(e => e.objectClass && e.capabilities.length > 0)
      .forEach(e => groups.push({ objectClass: e.objectClass, capabilityNames: e.capabilities }));
    this.capabilitiesChange.emit(groups);
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
