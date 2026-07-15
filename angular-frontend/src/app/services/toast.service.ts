/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Injectable, signal } from '@angular/core';

export type ToastType = 'success' | 'warning' | 'danger' | 'info';

export interface ToastMessage {
  title: string;
  message: string;
  type: ToastType;
}

@Injectable({
  providedIn: 'root'
})
export class ToastService {

  readonly toast = signal<ToastMessage | null>(null);

  show(
    title: string,
    message: string,
    type: ToastType = 'info'
  ): void {
    console.log(message)
    this.toast.set({
      title,
      message,
      type
    });
  }

  close(): void {
    this.toast.set(null);
  }
}
