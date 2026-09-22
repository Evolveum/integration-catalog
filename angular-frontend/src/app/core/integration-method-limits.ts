/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

/**
 * Longest limitations text a form accepts, mirroring IntegrationMethod.LIMITATIONS_MAX on the
 * backend, which refuses anything longer. The column holds 1000, so the two can be raised together
 * without touching the schema.
 */
export const LIMITATIONS_MAX = 500;
