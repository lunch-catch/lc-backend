package com.launchcatch.campaign.template.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 템플릿 생성 가능 수를 세는 행이 하나만 있다. 늘어나는 쪽만 다루고, 값을 올리는 건 TemplateQuotaRepository의 조건부 UPDATE로만 한다.
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
