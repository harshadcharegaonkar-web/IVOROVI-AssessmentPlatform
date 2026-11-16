# Demo programme: QA and security findings register

Session 3 keeps this register. Each finding has a test that asserts the secure behaviour. That test was run enabled, failed on the current code, and stays `@Disabled` with the finding id until the fix. The owning stream fixes the code; Session 3 re-tests the fix and enables the test.

| Id | Severity | Area (owner) | Finding | Test | Status |
|---|---|---|---|---|---|
| QA-DP-1 | Medium | Malware scanning, `platform.scanning` (shared: A / Session 2) | clamd truncates an archive entry larger than `MaxFileSize`: it scans only the first `MaxFileSize` bytes and answers OK. This happens even with `AlertExceedsMax yes` (ClamAV 1.4.6, clamd and clamscan; debug output "trimming output size to maxfilesize"). `ClamAvScanner` then reports **CLEAN** for a file that was only partly scanned, which contradicts D-19. A 5 MB upload can carry such an entry: 21 MB of padding deflates to about 21 KB. XLSX imports are zip archives; PDFs and Office documents carry compressed streams. | `ClamAvRealScannerIT.contentBeyondTheScanLimitIsNeverReportedClean`. Reproduction: a zip whose entry holds 21 MB of zeros followed by a QA-only test signature (loaded into the test container) is CLEAN. The same signature after 10 MB is found (`contentDeepInsideACompressedArchiveWithinTheLimitsIsFound`, passing) | Open |

Suggested fix for QA-DP-1, for the owner to decide:
1. Before scanning, the application inspects zip containers (zip, XLSX, DOCX) and refuses, or marks ERROR, any whose declared or actual uncompressed size exceeds clamd's `MaxFileSize`. Apache POI's own zip-bomb limits only apply while parsing.
2. Alternatively, or as well, raise `MaxFileSize` and `MaxScanSize` above the largest expansion the application accepts.

The test then passes and is enabled.

Notes recorded during the same work, not findings:
- With ClamAV's **default** settings, an encrypted archive is answered **OK** (verified with the same image). The compose overlay sets `AlertEncrypted*`, so such files are reported as found (`Heuristics.Encrypted.*`). `ClamAvRealScannerIT.anEncryptedArchiveIsNeverClean` passes.
- The EICAR test file is detected only at the start of a file, so it cannot show scan depth. The real-clamd test therefore loads a QA-only body signature into its own container.
- With clamd stopped, a student document upload is stored with scan status ERROR and cannot be downloaded (native API smoke, 2026-11-16). Nothing rescans an ERROR document; the user uploads it again. This fails closed, as intended.
