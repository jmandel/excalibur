import type { DeviceProfile } from './types';

export const deviceProfiles: DeviceProfile[] = [
  {
    id: 'kindle_oasis',
    label: 'Paperwhite 2024 / 12th gen',
    description: 'Closest calibre built-in profile for the 7-inch 300ppi 2024 Paperwhite. Uses the 1264×1680 / 300ppi Kindle Oasis profile.',
    screen: '7-inch, 300ppi class',
    recommended: true,
  },
  { id: 'kindle_pw3', label: 'Paperwhite 3 / Voyage-era', description: 'Older 300ppi Paperwhite/Voyage-class profile.', screen: '1072×1430, 300ppi' },
  { id: 'kindle_pw', label: 'Older Paperwhite', description: 'Older 212ppi Paperwhite profile.', screen: '658×940, 212ppi' },
  { id: 'kindle_scribe', label: 'Kindle Scribe', description: 'Large-format Kindle Scribe.', screen: '1860×2480, 300ppi' },
  { id: 'kindle_voyage', label: 'Kindle Voyage', description: 'Kindle Voyage 300ppi profile.', screen: '1080×1430, 300ppi' },
  { id: 'kindle_oasis', label: 'Kindle Oasis', description: '7-inch Kindle Oasis profile.', screen: '1264×1680, 300ppi' },
  { id: 'kindle', label: 'Classic Kindle', description: 'Older/basic Kindle profile.', screen: '525×640' },
  { id: 'default', label: 'Generic/default', description: 'calibre generic output defaults.', screen: 'Generic' },
];

export const defaultDeviceProfile = 'kindle_oasis';
