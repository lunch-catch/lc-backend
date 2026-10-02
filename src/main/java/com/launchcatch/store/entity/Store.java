package com.launchcatch.store.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "store")
@Data
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class Store extends BaseTimeEntity {

    @Column(name = "name")
    public String name;

    @Column(name = "business_number")
    public String businessNumber;

    @Enumerated
    @Column(name = "status")
    private StoreStatus status;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StoreMenu> menus = new ArrayList<>();

    public void setId(Long id) {
        super.getId();
    }

    public List<StoreMenu> getMenus() {
        return menus;
    }
}
