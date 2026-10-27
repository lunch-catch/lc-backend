package com.lunchcatch.store;

public record StoreBasicInfo(String name, String categoryCode, String representativeName,
                             String businessRegistrationNumber, String phone) {
    public StoreBasicInfo {
        requireText(name, 100);
        requireText(representativeName, 100);
        if (categoryCode == null || !categoryCode.matches(
                "KOREAN|CHINESE|JAPANESE|WESTERN|BUNSIK|ASIAN|FAST_FOOD|CAFE_DESSERT|OTHER")) {
            throw new IllegalArgumentException("지원하지 않는 업종입니다.");
        }
        if (businessRegistrationNumber == null || !businessRegistrationNumber.matches("[0-9]{10}")) {
            throw new IllegalArgumentException("사업자등록번호는 숫자 10자리여야 합니다.");
        }
        if (phone == null || !phone.matches("[0-9]{2,4}-[0-9]{3,4}-[0-9]{4}|[0-9]{9,12}")) {
            throw new IllegalArgumentException("전화번호 형식이 올바르지 않습니다.");
        }
    }

    private static void requireText(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("필수 문자열의 길이가 올바르지 않습니다.");
        }
    }
}
