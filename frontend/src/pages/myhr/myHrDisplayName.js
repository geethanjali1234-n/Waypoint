export function personDisplayName(profile, session) {
  const name = profile?.name?.trim() || [profile?.firstName, profile?.lastName].filter(Boolean).join(' ').trim() || session?.name?.trim();
  return name ? name.replace(/(^|\s)(\S)/g, (_, space, letter) => space + letter.toLocaleUpperCase()) : session?.loginId || 'My HR';
}
