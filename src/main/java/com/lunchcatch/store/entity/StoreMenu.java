package com.lunchcatch.store.entity;

import com.lunchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
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

    @Column(length = 500)
    private String description;

    @Column(name = "sort_order", nullable = false, columnDefinition = "TINYINT")
    private int sortOrder;

    private StoreMenu(Long storeId, String name, long price, String description, int sortOrder) {
        if (sortOrder < 1 || sortOrder > 3) {
            throw new IllegalArgumentException("sortOrder must be 1~3: " + sortOrder);
        }
        if (price < 0) {
            throw new IllegalArgumentException("price must not be negative: " + price);
        }
        this.storeId = Objects.requireNonNull(storeId, "storeId");
        this.name = Objects.requireNonNull(name, "name");
        this.price = price;
        this.description = description;
        this.sortOrder = sortOrder;
    }

    // sortOrder는 점주 입력 없이 그 가게의 기존 메뉴 수 다음 순번으로 호출자가 매긴다(#389 결정).
    public static StoreMenu register(Long storeId, String name, long price, String description, int sortOrder) {
        return new StoreMenu(storeId, name, price, description, sortOrder);
    }
}
