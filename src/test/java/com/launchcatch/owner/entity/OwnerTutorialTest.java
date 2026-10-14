package com.launchcatch.owner.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class OwnerTutorialTest {
    @Test
    void ACTIVE_점주는_반복해서_완료할_수_있다() {
        var owner = Owner.create("owner@example.com", "hash");
        ReflectionTestUtils.setField(owner, "status", OwnerStatus.ACTIVE);

        owner.completeTutorial();
        owner.completeTutorial();

        assertThat(owner.isTutorialViewed()).isTrue();
        assertThat(owner.getStatus()).isEqualTo(OwnerStatus.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void ACTIVE가_아니면_직접_호출해도_완료할_수_없다(OwnerStatus status) {
        var owner = Owner.create("owner@example.com", "hash");
        ReflectionTestUtils.setField(owner, "status", status);

        assertThatThrownBy(owner::completeTutorial).isInstanceOf(IllegalStateException.class);

        assertThat(owner.isTutorialViewed()).isFalse();
        assertThat(owner.getStatus()).isEqualTo(status);
    }

    @Test
    void 완료_후_정지된_점주도_전제조건을_검사한다() {
        var owner = Owner.create("owner@example.com", "hash");
        ReflectionTestUtils.setField(owner, "status", OwnerStatus.ACTIVE);
        owner.completeTutorial();
        ReflectionTestUtils.setField(owner, "status", OwnerStatus.SUSPENDED);

        assertThatThrownBy(owner::completeTutorial).isInstanceOf(IllegalStateException.class);

        assertThat(owner.isTutorialViewed()).isTrue();
        assertThat(owner.getStatus()).isEqualTo(OwnerStatus.SUSPENDED);
    }
}
