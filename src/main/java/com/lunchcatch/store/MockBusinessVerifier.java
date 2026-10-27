package com.lunchcatch.store;

// 외부 사업자 조회를 호출하지 않는 Mock이며 실패용 번호는 API 명세에 공개한다.
public final class MockBusinessVerifier {
    private MockBusinessVerifier() { }

    public static BusinessVerificationStatus verify(String number) {
        if (number == null || !number.matches("[0-9]{10}")) {
            throw new IllegalArgumentException("사업자등록번호는 숫자 10자리여야 합니다.");
        }
        return "0000000000".equals(number) ? BusinessVerificationStatus.FAILED : BusinessVerificationStatus.VERIFIED;
    }
}
