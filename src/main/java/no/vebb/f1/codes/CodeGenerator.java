package no.vebb.f1.codes;

import java.security.SecureRandom;

public class CodeGenerator {

	private static final SecureRandom random = new SecureRandom();

	public static int getVerificationCode() {
		return random.nextInt(900000000) + 100000000;
	}
	
	public static long getReferralCode() {
		return random.nextLong(Long.MAX_VALUE - 1000000000000000000L) + 1000000000000000000L;
	}
}
