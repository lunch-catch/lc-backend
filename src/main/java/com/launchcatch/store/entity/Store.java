package com.launchcatch.store.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.launchcatch.store.StoreBasicInfo;
import com.launchcatch.store.StoreLocation;
import com.launchcatch.store.BusinessVerificationStatus;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "store")
@AttributeOverride(name = "id", column = @Column(name = "store_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store extends BaseTimeEntity {
    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "category_code", nullable = false, length = 30)
    private String categoryCode;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "kakao_place_id", length = 50)
    private String kakaoPlaceId;

    @Column(name = "road_address", length = 255)
    private String roadAddress;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Column(name = "representative_name", nullable = false, length = 100)
    private String representativeName;

    @Column(length = 30)
    private String phone;

    @Column(name = "business_registration_number", length = 10)
    private String businessRegistrationNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_verification_status", length = 20)
    private BusinessVerificationStatus businessVerificationStatus;

    @Column(name = "business_verified_at")
    private LocalDateTime businessVerifiedAt;

    @Column(nullable = false)
    private boolean finalized;

    private Store(Long ownerId, StoreBasicInfo info, LocalDateTime verifiedAt) {
        if (ownerId == null || ownerId <= 0) {
            throw new IllegalArgumentException("유효한 점주 ID가 필요합니다.");
        }
        this.ownerId = ownerId;
        replaceVerifiedBasicInfo(info, verifiedAt);
    }

    // 호출자는 사업자 Mock 검증 성공을 확인한 뒤 미확정 가게를 생성한다.
    public static Store createDraft(Long ownerId, StoreBasicInfo info, LocalDateTime verifiedAt) {
        return new Store(ownerId, info, verifiedAt);
    }

    public void replaceLocation(StoreLocation location) {
        Objects.requireNonNull(location, "위치 정보가 필요합니다.");
        if (finalized) {
            throw new IllegalStateException("최종 등록한 가게의 온보딩 정보는 변경할 수 없습니다.");
        }
        kakaoPlaceId = location.kakaoPlaceId();
        roadAddress = location.roadAddress();
        latitude = location.latitude();
        longitude = location.longitude();
    }

    // 호출자는 본인 소유의 미확정 가게인지 확인하고 사업자 정보를 재검증해야 한다.
    public void replaceVerifiedBasicInfo(StoreBasicInfo info, LocalDateTime verifiedAt) {
        Objects.requireNonNull(info, "기본 정보가 필요합니다.");
        Objects.requireNonNull(verifiedAt, "검증 성공 시각이 필요합니다.");
        if (finalized) {
            throw new IllegalStateException("최종 등록한 가게의 온보딩 정보는 변경할 수 없습니다.");
        }
        name = info.name();
        categoryCode = info.categoryCode();
        representativeName = info.representativeName();
        businessRegistrationNumber = info.businessRegistrationNumber();
        phone = info.phone();
        businessVerificationStatus = BusinessVerificationStatus.VERIFIED;
        businessVerifiedAt = verifiedAt;
    }
}
