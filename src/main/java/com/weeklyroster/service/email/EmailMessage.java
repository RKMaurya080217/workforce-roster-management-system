package com.weeklyroster.service.email;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Encapsulates an outbound transactional email message.
 */
public class EmailMessage {
    private final String toEmail;
    private final String toName;
    private final String fromEmail;
    private final String fromName;
    private final String subject;
    private final String textBody;
    private final String htmlBody;
    private final String replyTo;
    private final List<EmailAttachment> attachments;

    private EmailMessage(Builder builder) {
        this.toEmail = builder.toEmail;
        this.toName = builder.toName;
        this.fromEmail = builder.fromEmail;
        this.fromName = builder.fromName;
        this.subject = builder.subject;
        this.textBody = builder.textBody;
        this.htmlBody = builder.htmlBody;
        this.replyTo = builder.replyTo;
        this.attachments = Collections.unmodifiableList(new ArrayList<>(builder.attachments));
    }

    public String getToEmail() { return toEmail; }
    public String getToName() { return toName; }
    public String getFromEmail() { return fromEmail; }
    public String getFromName() { return fromName; }
    public String getSubject() { return subject; }
    public String getTextBody() { return textBody; }
    public String getHtmlBody() { return htmlBody; }
    public String getReplyTo() { return replyTo; }
    public List<EmailAttachment> getAttachments() { return attachments; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String toEmail;
        private String toName;
        private String fromEmail;
        private String fromName;
        private String subject;
        private String textBody;
        private String htmlBody;
        private String replyTo;
        private List<EmailAttachment> attachments = new ArrayList<>();

        public Builder to(String email) {
            this.toEmail = email;
            return this;
        }

        public Builder to(String email, String name) {
            this.toEmail = email;
            this.toName = name;
            return this;
        }

        public Builder from(String email) {
            this.fromEmail = email;
            return this;
        }

        public Builder from(String email, String name) {
            this.fromEmail = email;
            this.fromName = name;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder textBody(String textBody) {
            this.textBody = textBody;
            return this;
        }

        public Builder htmlBody(String htmlBody) {
            this.htmlBody = htmlBody;
            return this;
        }

        public Builder replyTo(String replyTo) {
            this.replyTo = replyTo;
            return this;
        }

        public Builder addAttachment(EmailAttachment attachment) {
            if (attachment != null) {
                this.attachments.add(attachment);
            }
            return this;
        }

        public Builder addAttachments(List<EmailAttachment> attachments) {
            if (attachments != null) {
                this.attachments.addAll(attachments);
            }
            return this;
        }

        public EmailMessage build() {
            return new EmailMessage(this);
        }
    }
}
