package com.ivorovi.support;

import com.ivorovi.platform.mail.EmailMessage;
import com.ivorovi.platform.mail.EmailSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Records every message (so tests can follow invitation links), then sends it over SMTP like production. */
public class RecordingEmailSender implements EmailSender {

    public final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
    private final JavaMailSender smtp;
    private final String from;

    public RecordingEmailSender(JavaMailSender smtp, String from) {
        this.smtp = smtp;
        this.from = from;
    }

    /** Number of upcoming sends that fail (simulates an unavailable mail relay). */
    public final java.util.concurrent.atomic.AtomicInteger failNext = new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public void send(EmailMessage message) {
        if (failNext.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) throw new org.springframework.mail.MailSendException("simulated relay failure");
        sent.add(message);
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(message.to());
        m.setSubject(message.subject());
        m.setText(message.textBody());
        smtp.send(m);
    }

    public String lastTokenFor(String email) {
        for (int i = sent.size() - 1; i >= 0; i--) {
            EmailMessage m = sent.get(i);
            if (m.to().equalsIgnoreCase(email)) {
                var matcher = java.util.regex.Pattern.compile("#token=([A-Za-z0-9_-]{43})").matcher(m.textBody());
                if (matcher.find()) return matcher.group(1);
            }
        }
        throw new AssertionError("No invitation email to " + email);
    }
}
