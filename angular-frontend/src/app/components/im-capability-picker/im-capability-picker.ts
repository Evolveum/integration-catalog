/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, Output, EventEmitter, Input, OnChanges, SimpleChanges, signal, OnInit, HostListener, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApplicationService } from '../../services/application.service';
import { formatCapabilityLabel } from '../../core/capability-label';
import { CapabilityState, IntegrationMethodObjectCapabilities } from '../../models/application-detail.model';

/**
 * Every object sent supports something (the backend rule), and there is at least one object unless
 * {@code allowNone}: a superuser may publish a method without capabilities. The backend never
 * required an object, so that exception lives here only.
 */
export function imCapabilitiesValid(groups: IntegrationMethodObjectCapabilities[], allowNone = false): boolean {
  return (allowNone || groups.length > 0) && groups.every(g => g.capabilities.some(c => c.state === 'YES'));
}

interface Entry {
  objectClass: string;
  states: Record<string, CapabilityState>;
  isOpen: boolean;
}

/**
 * Capability picker of an integration method: every capability offered to methods gets a state per
 * object. Connectors and the request form use {@link CapabilityPicker}, which only lists what is supported.
 */
@Component({
  selector: 'app-im-capability-picker',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './im-capability-picker.html',
  styleUrls: ['../capability-picker/capability-picker.scss', './im-capability-picker.scss']
})
export class ImCapabilityPicker implements OnInit, OnChanges {
  @Input() initialCapabilities: IntegrationMethodObjectCapabilities[] = [];
  @Output() capabilitiesChange = new EventEmitter<IntegrationMethodObjectCapabilities[]>();

  protected readonly stateOptions: { value: CapabilityState; label: string }[] = [
    { value: 'YES', label: 'Yes' },
    { value: 'NO', label: 'No' },
    { value: 'UNKNOWN', label: 'Unknown' }
  ];

  protected readonly isLoading = signal<boolean>(false);
  protected readonly available = signal<string[]>([]);
  protected readonly entries = signal<Entry[]>([{ objectClass: '', states: {}, isOpen: false }]);

  constructor(private applicationService: ApplicationService, private elementRef: ElementRef) {}

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.elementRef.nativeElement.contains(event.target)) {
      this.entries.update(es => es.map(e => ({ ...e, isOpen: false })));
    }
  }

  ngOnInit(): void {
    if (this.initialCapabilities.length > 0) {
      this.applyInitialCapabilities();
    }
    this.isLoading.set(true);
    this.applicationService.getCapabilities().subscribe({
      next: (caps) => {
        this.available.set(caps
          .filter(c => c.globality === 'SPECIFIC' && c.offeredForMethod)
          .sort((a, b) => (a.displayOrder ?? 0) - (b.displayOrder ?? 0))
          .map(c => c.name));
        this.isLoading.set(false);
      },
      error: () => this.isLoading.set(false)
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['initialCapabilities'] && this.initialCapabilities?.length) {
      this.applyInitialCapabilities();
    }
  }

  private applyInitialCapabilities(): void {
    const current = this.entries();
    const fromInit: Entry[] = this.initialCapabilities
      .filter(g => g.objectClass !== 'Global')
      .map(g => ({
        objectClass: g.objectClass,
        states: Object.fromEntries(g.capabilities.map(c => [c.name, c.state])),
        isOpen: current.find(e => e.objectClass === g.objectClass)?.isOpen ?? false
      }));
    // Keep objects typed in but not yet known to the parent.
    const inProgress = current.filter(e => e.objectClass && !fromInit.some(f => f.objectClass === e.objectClass));
    const merged = [...fromInit, ...inProgress];
    this.entries.set(merged.length > 0 ? merged : [{ objectClass: '', states: {}, isOpen: false }]);
  }

  private emit(): void {
    this.capabilitiesChange.emit(this.entries()
      .filter(e => e.objectClass)
      .map(e => ({
        objectClass: e.objectClass,
        capabilities: this.available().map(name => ({ name, state: this.stateOf(e, name) }))
      })));
  }

  protected stateOf(entry: Entry, cap: string): CapabilityState {
    return entry.states[cap] ?? 'UNKNOWN';
  }

  protected hasYes(entry: Entry): boolean {
    return this.available().some(cap => this.stateOf(entry, cap) === 'YES');
  }

  protected fmt(cap: string): string {
    return formatCapabilityLabel(cap);
  }

  protected addEntry(): void {
    this.entries.update(es => [...es, { objectClass: '', states: {}, isOpen: false }]);
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
    this.entries.update(es => es.map((e, idx) => idx === i ? { ...e, isOpen: !e.isOpen } : e));
  }

  protected onStateChange(i: number, cap: string, state: string): void {
    this.entries.update(es => es.map((e, idx) =>
      idx !== i ? e : { ...e, states: { ...e.states, [cap]: state as CapabilityState } }
    ));
    this.emit();
  }
}
