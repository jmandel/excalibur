export type Device = { id: string; label: string };

// "Paperwhite" alone is ambiguous — it spans many generations and two screen
// sizes — so each option names the recognizable model(s) plus year and screen.
// Kept terse so the picker doesn't overflow; several map to one calibre profile.
export const devices: Device[] = [
  { id: 'kindle_oasis', label: 'Paperwhite 2024 / 12th gen · 7″' },
  { id: 'kindle_pw3', label: 'Paperwhite 2015–2021 · 6″' },
  { id: 'kindle_scribe', label: 'Scribe · 10.2″' },
  { id: 'kindle_voyage', label: 'Voyage 2014 · 6″' },
  { id: 'kindle_pw', label: 'Paperwhite 2012–2013 · 6″' },
  { id: 'kindle', label: 'Basic Kindle · 6″' },
];

export const defaultDevice = 'kindle_oasis';
export const deviceLabel = (id: string) => devices.find((d) => d.id === id)?.label ?? id;
