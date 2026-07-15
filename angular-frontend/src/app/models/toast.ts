/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

export interface ToastMessage {
  title: string;
  message: string;
  type: 'success' | 'warning' | 'danger' | 'info';
}
