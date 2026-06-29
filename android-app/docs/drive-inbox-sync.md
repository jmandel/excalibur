# Drive inbox sync

The Drive inbox uses Android's Storage Access Framework rather than Google OAuth. The user
chooses a folder through the system folder picker; if that folder is backed by Google Drive,
the Drive app/provider handles network access and exposes files as document Uris.

Behavior:

- Sync is one-way from the selected folder into Excalibur's app-private library.
- Only top-level files are scanned in the first version.
- Supported extensions: EPUB, MOBI, AZW, AZW3, PRC, POBI.
- AZW3 files are imported as already ready; other formats are queued for calibre conversion.
- Removing a file from Drive does not remove anything from Excalibur.
- Previously seen SAF document identities are skipped before download; SHA-256 import dedupe
  still catches duplicate content if the provider reports a changed identity.

Daily background sync is implemented with WorkManager and is deliberately constrained:

- network connected
- charging
- battery not low
- storage not low

This means the daily sync is opportunistic, not exact-clock scheduling. If the phone is not
charging, Android waits until the constraints are satisfied.

Manual "Sync Drive now" runs immediately from Settings, then starts the normal conversion
service if newly imported files need conversion.
