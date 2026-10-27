package com.lunchcatch.campaign.entity;

// campaign_status_log.actor_type 컬럼의 값이다. 전이를 일으킨 쪽이 누구인지 적는다.
public enum ActorType {
    ADMIN,
    OWNER,
    SYSTEM
}
