package com.lunchcatch.ops.entity;

import com.lunchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 플랫폼 설정값 한 행이다. 관리자가 쓰는 쓰기 경로(갱신, 이력)는 이번 이슈 범위 밖이라
 * 아직 없다. 지금은 PlatformSettingQueryService가 읽기만 한다.
 */
@Entity
@Table(name = "platform_setting")
@AttributeOverride(name = "id", column = @Column(name = "platform_setting_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlatformSetting extends BaseTimeEntity {

    @Column(name = "setting_key", nullable = false, length = 50, updatable = false)
    private String settingKey;

    @Column(name = "setting_value", nullable = false, length = 500)
    private String settingValue;

    @Column(length = 200)
    private String description;

    @Column(name = "updated_by")
    private Long updatedBy;
}
