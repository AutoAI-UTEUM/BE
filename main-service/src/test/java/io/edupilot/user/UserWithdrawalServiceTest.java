package io.edupilot.user;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.edupilot.auth.GoogleIdTokenVerifier;
import io.edupilot.auth.GoogleProfile;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.dto.WithdrawRequest;

class UserWithdrawalServiceTest {
	private final UserService users = mock(UserService.class);
	private final GoogleIdTokenVerifier google = mock(GoogleIdTokenVerifier.class);
	private final UserWithdrawalService service = new UserWithdrawalService(users, google);

	@Test
	void invalidGoogleProofNeverOpensDeletionTransaction() {
		when(google.verify("invalid")).thenThrow(new BusinessException(ErrorCode.TOKEN_INVALID));
		assertThatThrownBy(() -> service.withdraw(1L, new WithdrawRequest(null, "invalid")))
			.isInstanceOf(BusinessException.class);
		verifyNoInteractions(users);
	}

	@Test
	void serverVerifiedSubjectIsPassedToDeletionAfterProviderVerification() {
		when(google.verify("proof")).thenReturn(new GoogleProfile("server-sub", "synthetic@example.com", "Synthetic"));
		service.withdraw(1L, new WithdrawRequest(null, "proof"));
		var order = inOrder(google, users);
		order.verify(google).verify("proof");
		order.verify(users).withdrawGoogle(1L, "server-sub");
	}

	@Test
	void ambiguousOrMissingProofIsRejectedAndLocalPathDoesNotCallGoogle() {
		assertThatThrownBy(() -> service.withdraw(1L, new WithdrawRequest(null, null))).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> service.withdraw(1L, new WithdrawRequest("password", "proof"))).isInstanceOf(BusinessException.class);
		service.withdraw(1L, new WithdrawRequest("password"));
		verify(users).withdraw(1L, "password");
		verifyNoInteractions(google);
	}
}
