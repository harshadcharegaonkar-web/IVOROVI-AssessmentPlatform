package com.ivorovi.platform.mail;

/** Outbound email port. The notifications module (Phase 2) adds templating, preferences and retries on top. */
public interface EmailSender {

    void send(EmailMessage message);
}
