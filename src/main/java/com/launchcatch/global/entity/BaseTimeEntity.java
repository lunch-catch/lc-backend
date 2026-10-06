package com.launchcatch.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/*
 * 모든 엔티티의 공통 뼈대. 식별자와 생성, 수정 시각을 갖는다.
 * V1 의 63개 테이블 전부가 created_at 과 updated_at 을 가지므로 베이스는 이것 하나다.
 *
 * 이력과 로그 테이블도 updated_at 을 갖는다. 뼈대를 둘로 나누면 "이 테이블은 수정되는가"
 * 라는 판단이 상속 선택으로 들어와, 나중에 보정이나 마스킹으로 한 번이라도 수정하는 순간
 * 테이블과 엔티티를 함께 바꿔야 한다. 그 판단은 뼈대가 아니라 도메인 메서드가 갖는다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
public abstract class BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
