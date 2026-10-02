package com.launchcatch.campaign.entity;

import com.launchcatch.global.entity.BaseMutableTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "campaign")
public class Campaign extends BaseMutableTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "daily_budget", nullable = false)
    private Long dailyBudget;

    protected Campaign() {
    }

    public Long getId() {
        return id;
    }

    public Long getDailyBudget() {
        return dailyBudget;
    }
}
