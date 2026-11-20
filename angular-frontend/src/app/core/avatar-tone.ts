/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

/** How many .avatar-tone-N classes styles.scss generates: the length of $avatar-gradients. */
const AVATAR_TONES = 5;

/** The .avatar-tone-N class of an application's letter avatar; the same name always gets the same tone. */
export function avatarToneClass(name: string): string {
  const index = name.split('').reduce((sum, ch) => sum + ch.charCodeAt(0), 0) % AVATAR_TONES;
  return `avatar-tone-${index}`;
}
