package com.launchcatch.store.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "store_menu")
@AttributeOverride(name = "id", column = @Column(name = "store_menu_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreMenu extends BaseTimeEntity {
    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private long price;
}
