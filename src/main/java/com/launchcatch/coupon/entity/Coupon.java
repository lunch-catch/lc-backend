package com.launchcatch.coupon.entity;

import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.global.entity.BaseMutableTimeEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "member_coupon")
public class Coupon extends BaseMutableTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 발급 화면에서 캠페인 이름을 함께 보여줘야 해서 객체로 들고 있다
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    protected Coupon() {
    }

    public Long getId() {
        return id;
    }

    public Campaign getCampaign() {
        return campaign;
    }
}
