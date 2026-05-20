/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

export interface CategoryCount {
  displayName: string; // application_tag.display_name (where tag_type = CATEGORY)
  count: number;       // computed: count of application_application_tag rows
}
