package com.lunchcatch.campaign.template.entity;

import com.lunchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 템플릿 생성 가능 수를 세는 행이 하나만 있다. 값을 올리거나 내리는 건 TemplateQuotaRepository의
 * 조건부 UPDATE로만 한다(#517부터 삭제로 내리는 경로도 생겼다).
 * 읽어서 더하고 저장하는 방식은 동시 요청에서 레이스가 생겨 쓰지 않는다.
 */
@Entity
@Table(name = "template_quota")
@AttributeOverride(name = "id", column = @Column(name = "template_quota_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TemplateQuota extends BaseTimeEntity {

    @Column(name = "current_count", nullable = false)
    private int currentCount;
}
