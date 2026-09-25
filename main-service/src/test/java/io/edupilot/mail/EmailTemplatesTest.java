package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.edupilot.notification.NotificationType;

class EmailTemplatesTest {

	private final EmailTemplates templates = new EmailTemplates(new MailProperties(
		true, "logging", "no-reply@uteum.com", "",
		"https://dev.uteum.com", "ap-northeast-2"
	));

	@Test
	void resetAndVerifyTemplatesUseConfiguredBaseUrlAndKoreanFooter() {
		EmailTemplates.Template reset = templates.passwordReset("/reset?token=one", 30);
		assertThat(reset.type()).isEqualTo(EmailDeliveryType.PASSWORD_RESET);
		assertThat(reset.textBody()).contains(
			"https://dev.uteum.com/reset?token=one", "30분", "이 메일은 발신 전용입니다."
		);
		assertThat(templates.emailVerify("/verify?token=two").textBody())
			.contains("https://dev.uteum.com/verify?token=two");
	}

	@Test
	void notificationAcceptsInAppTypeAndEscapesHtml() {
		EmailTemplates.Template template = templates.notification(
			NotificationType.EXAM_GRADED,
			"채점 <완료>",
			"점수를 확인하세요 & 다시 학습하세요",
			"/classrooms/1/exams/2"
		);
		assertThat(template.type()).isEqualTo(EmailDeliveryType.NOTIFICATION);
		assertThat(template.textBody()).contains("채점 <완료>");
		assertThat(template.htmlBody()).contains("채점 &lt;완료&gt;", "확인하세요 &amp;")
			.doesNotContain("<완료>");
	}
}
