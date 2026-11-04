# DPDP legal review — brief for counsel

Status: **brief prepared 2026-11-04; review not yet performed.** The platform team cannot perform a legal review.
This brief gives qualified counsel (engaged by the product owner) the facts and questions they need. Until counsel's
written conclusions are received and implemented, release blocker RB-5 (KI-48) stays open, and no real candidate may
be processed (decision-register C-7).

## 1. The system in brief

Ivorovi is a multi-tenant SaaS (also deployable in a customer's private cloud or on premises) for colleges and
universities in India. Each institution is a separate tenant and acts as **data fiduciary**; Ivorovi acts as **data
processor** (to be confirmed by counsel, including for SaaS operations, support access and AI providers). Planned
Phase 2 processing:

| Processing | Personal data | Data principals |
|---|---|---|
| Admission applications | identity and contact details, date of birth, category/reservation data, academic history, documents, photo | applicants, often **under 18**; parents/guardians |
| Entrance and academic examinations | responses, scores, ranks, attempt logs, IP/device data | applicants, students |
| Proctoring (optional per assessment) | webcam snapshots, browser signals, integrity flags, incident decisions | candidates (possibly minors) |
| Evaluation with optional AI assistance | answer text sent to an AI model (pseudonymised), suggestions, human decisions | candidates, students |
| Placement | scorecards, academic records shared with external recruiters | students |
| Integrations | data exchanged with SIS/LMS/ERP, webhooks to institution systems | students, staff |

Technical controls already in place, or designed (not a compliance claim): tenant isolation, scoped access control, field
encryption, audit trail, minimised webhook payloads, read-only API clients with live revalidation, retention jobs.

## 2. Questions for counsel

### A. Under-18 applicants and parental consent
1. When does verifiable parental consent apply to applicants (age at application, at test, at admission)?
2. What constitutes “verifiable” consent for an online application: guardian email/mobile OTP, guardian account,
   DigiLocker-based verification, signed form? Is there an accepted standard?
3. Is age self-declaration acceptable for triggering the parental flow, or is verification required?
4. Which processing is restricted for children: proctoring, behavioural monitoring, AI processing, profiling for
   recommendations? Can any exemption for educational institutions apply, and on what conditions?
5. Must parental consent be renewed when the applicant turns 18 during the cycle?

### B. Proctoring consent and transparency
6. Is consent the right basis for webcam proctoring in an admission test, or is it a legitimate use / necessary for
   the examination? Can a candidate refuse and still be assessed (alternative arrangements)?
7. Required content of the notice: what is collected, retention, who reviews, automated processing, rights, grievance
   officer.
8. Is an AI-generated integrity flag (always reviewed by a human; never a decision) “profiling” requiring additional
   disclosure?
9. Retention limits for snapshots and flags, including during appeals.

### C. Recruiter data sharing
10. Basis for sharing scorecards and academic records with recruiters: explicit per-drive consent (as designed)?
11. Is the institution or the recruiter the fiduciary once data is shared? What contractual terms are needed?
12. Withdrawal of consent after sharing: obligations and practical limits.

### D. Retention, deletion and access requests
13. Confirm or adjust the proposed retention schedule (`phase-2/18-business-readiness.md`), considering university,
    regulator and statutory record-keeping obligations (results registers, admission records).
14. Erasure requests vs. statutory retention: what must be kept, in what form (e.g. pseudonymised)?
15. Timelines and verification for access, correction, erasure and grievance requests. Who responds: institution or
    platform?
16. Data-breach notification duties and timelines for the institution and for Ivorovi as processor.

### E. Candidate rights and institutional responsibilities
17. Required grievance-redressal mechanism and contact (institution DPO / grievance officer) inside the product.
18. Rights regarding examination results: re-evaluation, explanation of AI-assisted evaluation, human review.
19. Cross-border transfer: AI providers or hosting outside India. Conditions and restrictions.
20. Processor agreement terms between Ivorovi and institutions (sub-processors: cloud, email, AI providers).

## 3. Requested outputs

Written opinion on A–E; approved notice and consent texts (English plus the required Indian languages); retention
schedule; data-principal request procedure; processor agreement template; list of conditions to implement before
real-candidate processing.

## 4. Engineering follow-up after the opinion

Consent and parental-consent flows (2.3), notices in the candidate portal, retention jobs per data class, a
data-principal request workflow (closes KI-48 / RB-5 together with the legal sign-off), and recruiter consent records
(2.13).
