/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, Output, EventEmitter, Input, OnChanges, SimpleChanges, signal, OnInit } from '@angular/core';
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

function newEntry(): Entry {
  return { objectClass: '', states: {}, isOpen: true };
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
  styleUrls: ['./im-capability-picker.scss']
})
export class ImCapabilityPicker implements OnInit, OnChanges {
  @Input() initialCapabilities: IntegrationMethodObjectCapabilities[] = [];
  /** Only marks the heading; validity is checked by the parent via {@link imCapabilitiesValid}. */
  @Input() required = false;
  @Output() capabilitiesChange = new EventEmitter<IntegrationMethodObjectCapabilities[]>();

  protected readonly stateOptions: { value: CapabilityState; label: string; icon: string }[] = [
    { value: 'YES', label: 'Supported', icon: 'fa-check' },
    { value: 'NO', label: 'Not supported', icon: 'fa-xmark' },
    { value: 'UNKNOWN', label: 'Unknown', icon: 'fa-question' }
  ];

  protected readonly isLoading = signal<boolean>(false);
  protected readonly available = signal<string[]>([]);
  protected readonly entries = signal<Entry[]>([newEntry()]);

  constructor(private applicationService: ApplicationService) {}

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
    // On the first load only the first object starts expanded; afterwards each keeps its own state.
    const firstLoad = !current.some(e => e.objectClass);
    const fromInit: Entry[] = this.initialCapabilities
      .filter(g => g.objectClass !== 'Global')
      .map((g, idx) => ({
        objectClass: g.objectClass,
        states: Object.fromEntries(g.capabilities.map(c => [c.name, c.state])),
        isOpen: firstLoad ? idx === 0 : current.find(e => e.objectClass === g.objectClass)?.isOpen ?? false
      }));
    // Keep objects not yet known to the parent, including freshly added unnamed ones.
    const inProgress = current.filter(e => !e.objectClass
      ? !firstLoad
      : !fromInit.some(f => f.objectClass === e.objectClass));
    const merged = [...fromInit, ...inProgress];
    this.entries.set(merged.length > 0 ? merged : [newEntry()]);
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

  protected counts(entry: Entry): Record<'yes' | 'no' | 'unknown', number> {
    const states = this.available().map(cap => this.stateOf(entry, cap));
    return {
      yes: states.filter(s => s === 'YES').length,
      no: states.filter(s => s === 'NO').length,
      unknown: states.filter(s => s === 'UNKNOWN').length
    };
  }

  protected fmt(cap: string): string {
    return formatCapabilityLabel(cap);
  }

  protected addEntry(): void {
    this.entries.update(es => [...es, newEntry()]);
  }

  protected removeEntry(i: number): void {
    this.entries.update(es => es.filter((_, idx) => idx !== i));
    this.emit();
  }

  protected updateObjectClass(i: number, value: string): void {
    this.entries.update(es => es.map((e, idx) => idx !== i ? e : { ...e, objectClass: value }));
    this.emit();
  }

  protected toggleEntry(i: number): void {
    this.entries.update(es => es.map((e, idx) => idx === i ? { ...e, isOpen: !e.isOpen } : e));
  }

  protected onStateChange(i: number, cap: string, state: CapabilityState): void {
    this.entries.update(es => es.map((e, idx) =>
      idx !== i ? e : { ...e, states: { ...e.states, [cap]: state } }
    ));
    this.emit();
  }
}
