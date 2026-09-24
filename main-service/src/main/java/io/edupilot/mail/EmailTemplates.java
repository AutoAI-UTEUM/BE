package io.edupilot.mail;

import java.util.Objects;

import org.springframework.stereotype.Component;

import io.edupilot.notification.NotificationType;

@Component
public class EmailTemplates {

	private static final String FOOTER = "\n\n이 메일은 발신 전용입니다.";
	private final MailProperties properties;

	public EmailTemplates(MailProperties properties) {
		this.properties = properties;
	}

	public Template passwordReset(String link, int expiresMinutes) {
		String text = "비밀번호 재설정을 요청하셨습니다.\n" + absoluteLink(link)
			+ "\n이 링크는 " + expiresMinutes + "분 후 만료됩니다."
			+ "\n요청하지 않으셨다면 이 메일을 무시하세요." + FOOTER;
		return template("[UTEUM] 비밀번호 재설정", text, EmailDeliveryType.PASSWORD_RESET);
	}

	public Template emailVerify(String link) {
		String text = "이메일 주소를 인증하려면 아래 링크를 여세요.\n"
			+ absoluteLink(link) + FOOTER;
		return template("[UTEUM] 이메일 인증", text, EmailDeliveryType.EMAIL_VERIFY);
	}

	public Template notification(String title, String body, String link) {
		String text = title + "\n\n" + body + "\n" + absoluteLink(link) + FOOTER;
		return template("[UTEUM] " + title, text, EmailDeliveryType.NOTIFICATION);
	}

	public Template notification(
		NotificationType notificationType, String title, String body, String link
	) {
		// The in-app subtype can be passed through by future triggers; mail history stays NOTIFICATION.
		Objects.requireNonNull(notificationType);
		return notification(title, body, link);
	}

	private Template template(String subject, String text, EmailDeliveryType type) {
		String html = "<div style=\"font-family:sans-serif;white-space:pre-wrap\">"
			+ escape(text) + "</div>";
		return new Template(subject, text, html, type);
	}

	private String absoluteLink(String link) {
		if (link.startsWith("https://")) {
			return link;
		}
		return properties.baseUrl().replaceAll("/+$", "") + "/"
			+ link.replaceAll("^/+", "");
	}

	private String escape(String text) {
		return text.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&#39;");
	}

	public record Template(
		String subject,
		String textBody,
		String htmlBody,
		EmailDeliveryType type
	) {
		public EmailMessage to(String recipient) {
			return new EmailMessage(recipient, subject, textBody, htmlBody, type);
		}
	}
}
