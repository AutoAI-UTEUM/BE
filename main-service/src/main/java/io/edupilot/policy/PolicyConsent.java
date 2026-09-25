package io.edupilot.policy;

import java.time.Instant;

import io.edupilot.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "policy_consents")
public class PolicyConsent {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(name = "policy_type", nullable = false, length = 20)
	private PolicyType policyType;

	@Column(name = "policy_version", nullable = false, length = 20)
	private String policyVersion;

	@Column(name = "agreed_at", nullable = false)
	private Instant agreedAt;

	@Column(nullable = false, length = 45)
	private String ip;

	@Column(name = "user_agent", length = 255)
	private String userAgent;

	protected PolicyConsent() {
	}

	private PolicyConsent(
		User user, PolicyType policyType, String policyVersion,
		Instant agreedAt, String ip, String userAgent
	) {
		this.user = user;
		this.policyType = policyType;
		this.policyVersion = policyVersion;
		this.agreedAt = agreedAt;
		this.ip = ip;
		this.userAgent = userAgent;
	}

	public static PolicyConsent create(
		User user, PolicyType policyType, String policyVersion,
		Instant agreedAt, String ip, String userAgent
	) {
		return new PolicyConsent(user, policyType, policyVersion, agreedAt, ip, userAgent);
	}

	public Long getId() { return id; }
	public User getUser() { return user; }
	public PolicyType getPolicyType() { return policyType; }
	public String getPolicyVersion() { return policyVersion; }
	public Instant getAgreedAt() { return agreedAt; }
	public String getIp() { return ip; }
	public String getUserAgent() { return userAgent; }
}
