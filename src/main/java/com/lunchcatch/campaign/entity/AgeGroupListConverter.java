package com.lunchcatch.campaign.entity;

import com.lunchcatch.campaign.contract.AgeGroup;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/*
 * campaign.target_age_groups 는 콤마로 구분한 문자열 컬럼 하나다(마이그레이션의
 * chk_campaign_target_age_groups). 계약(CampaignInfo)은 List<AgeGroup>로 노출하기로
 * 정했으므로, 저장 형식이 엔티티 밖으로 새지 않게 이 컨버터가 그 경계에서 변환한다.
 */
@Converter
class AgeGroupListConverter implements AttributeConverter<List<AgeGroup>, String> {

    @Override
    public String convertToDatabaseColumn(List<AgeGroup> attribute) {
        return attribute.stream()
                .map(Enum::name)
                .collect(Collectors.joining(","));
    }

    @Override
    public List<AgeGroup> convertToEntityAttribute(String dbData) {
        return Arrays.stream(dbData.split(","))
                .map(AgeGroup::valueOf)
                .collect(Collectors.toList());
    }
}
