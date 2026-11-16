# Demo programme: QA and security findings register

Session 3 keeps this register. Each finding has a test that asserts the secure behaviour. That test was run enabled, failed on the current code, and stays `@Disabled` with the finding id until the fix. The owning stream fixes the code; Session 3 re-tests the fix and enables the test.

| Id | Severity | Area (owner) | Finding | Test | Status |
|---|---|---|---|---|---|
| QA-DP-1 | Medium | Malware scanning, `platform.scanning` (shared: A / Session 2) | clamd truncates an archive entry larger than `MaxFileSize`: it scans only the first `MaxFileSize` bytes and answers OK. This happens even with `AlertExceedsMax yes` (ClamAV 1.4.6, clamd and clamscan; debug output "trimming output size to maxfilesize"). `ClamAvScanner` then reports **CLEAN** for a file that was only partly scanned, which contradicts D-19. A 5 MB upload can carry such an entry: 21 MB of padding deflates to about 21 KB. XLSX imports are zip archives; PDFs and Office documents carry compressed streams. | `ClamAvRealScannerIT.contentBeyondTheScanLimitIsNeverReportedClean` (enabled, passing). Reproduction: a zip whose entry holds 21 MB of zeros followed by a QA-only test signature (loaded into the test container) was CLEAN. The same signature after 10 MB is found (`contentDeepInsideACompressedArchiveWithinTheLimitsIsFound`) | **Fixed** on `ds/s3-clamav-sbom` (confirmed by A as a defect against D-19). `ArchiveLimits` checks every zip container before clamd and returns ERROR when it exceeds the limits; covered by `ArchiveLimitsTest` (10) |

The fix for QA-DP-1 (as specified by A, 2026-11-16) is `platform.scanning.ArchiveLimits`, using `java.util.zip` only. Before clamd is asked, every zip container (ZIP, XLSX, DOCX and so on) is checked. The verdict is ERROR, never CLEAN, when any of these holds:
- an entry's declared or actual uncompressed size exceeds `ivorovi.scanning.max-file-size`, or the total (nested archives included) exceeds `max-scan-size`;
- an entry's actual size differs from its declared size;
- an entry uses a method other than stored or deflated;
- the archive is malformed or uses ZIP64;
- archives are nested deeper than 5 levels;
- the archive has more than 10,000 entries.

The defaults (20 MB / 100 MB) equal clamd's settings in the overlay, which also passes them explicitly, so the application accepts no expansion that clamd cannot scan in full. Encrypted entries are checked by their declared size and left to clamd, which reports them as found. A workbook written by the platform's own XLSX writer passes.

Notes recorded during the same work, not findings:
- With ClamAV's **default** settings, an encrypted archive is answered **OK** (verified with the same image). The compose overlay sets `AlertEncrypted*`, so such files are reported as found (`Heuristics.Encrypted.*`). `ClamAvRealScannerIT.anEncryptedArchiveIsNeverClean` passes.
- The EICAR test file is detected only at the start of a file, so it cannot show scan depth. The real-clamd test therefore loads a QA-only body signature into its own container.
- With clamd stopped, a student document upload is stored with scan status ERROR and cannot be downloaded (native API smoke, 2026-11-16). Nothing rescans an ERROR document; the user uploads it again. This fails closed, as intended.
