package com.launchcatch.campaign.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/*
 * StoreAudienceGender.ALL은 DB에 기존 CHECK 제약 그대로 'OTHER'로 저장된다(#218). .name()을
 * 그대로 쓰는 @Enumerated(EnumType.STRING)으로는 이 분리를 표현할 수 없어, 저장은
 * dbValue(getDbValue())로, 읽기는 그 값으로 역매핑해서 가져온다.
 */
@Converter
class StoreAudienceGenderConverter implements AttributeConverter<StoreAudienceGender, String> {

    @Override
    public String convertToDatabaseColumn(StoreAudienceGender attribute) {
        return attribute == null ? null : attribute.getDbValue();
    }

    @Override
    public StoreAudienceGender convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        for (StoreAudienceGender value : StoreAudienceGender.values()) {
            if (value.getDbValue().equals(dbData)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown store_audience.gender value: " + dbData);
    }
}
