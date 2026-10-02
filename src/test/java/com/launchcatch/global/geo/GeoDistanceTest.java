package com.launchcatch.global.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/*
 * 기대값은 운영 코드의 수식을 다시 쓰지 않고 바깥에서 따로 계산해 상수로 박았다.
 * 테스트가 같은 수식을 다시 계산하면 그 수식이 틀려도 함께 틀려서 통과한다.
 */
class GeoDistanceTest {

    // 서울시청
    private static final double CITY_HALL_LATITUDE = 37.5663;
    private static final double CITY_HALL_LONGITUDE = 126.9779;

    // 강남역
    private static final double GANGNAM_LATITUDE = 37.4979;
    private static final double GANGNAM_LONGITUDE = 127.0276;

    @Test
    void 같은_좌표는_거리가_0이다() {
        // when
        double meters = GeoDistance.meters(
                CITY_HALL_LATITUDE, CITY_HALL_LONGITUDE, CITY_HALL_LATITUDE, CITY_HALL_LONGITUDE);

        // then
        assertThat(meters).isZero();
    }

    @Test
    void 서울시청과_강남역_사이는_8778m다() {
        // when
        double meters = GeoDistance.meters(
                CITY_HALL_LATITUDE, CITY_HALL_LONGITUDE, GANGNAM_LATITUDE, GANGNAM_LONGITUDE);

        // then
        assertThat(meters).isCloseTo(8_778.019, within(0.01));
    }

    @Test
    void 위도_1도_차이는_111195m다() {
        // when
        double meters = GeoDistance.meters(37.0, 127.0, 38.0, 127.0);

        // then
        assertThat(meters).isCloseTo(111_195.080, within(0.01));
    }

    @Test
    void 적도에서_경도_1도_차이는_111195m다() {
        // when
        double meters = GeoDistance.meters(0.0, 127.0, 0.0, 128.0);

        // then
        assertThat(meters).isCloseTo(111_195.080, within(0.01));
    }

    @Test
    void 위도_37_5도에서_경도_1도_차이는_88216m로_줄어든다() {
        // when
        double meters = GeoDistance.meters(37.5, 127.0, 37.5, 128.0);

        // then
        assertThat(meters).isCloseTo(88_216.573, within(0.01));
    }

    @Test
    void 좌표_순서를_바꿔도_거리가_같다() {
        // when
        double forward = GeoDistance.meters(
                CITY_HALL_LATITUDE, CITY_HALL_LONGITUDE, GANGNAM_LATITUDE, GANGNAM_LONGITUDE);
        double backward = GeoDistance.meters(
                GANGNAM_LATITUDE, GANGNAM_LONGITUDE, CITY_HALL_LATITUDE, CITY_HALL_LONGITUDE);

        // then
        assertThat(forward).isEqualTo(backward);
    }

    @Test
    void 지구_반대편_두_점은_20015114m다() {
        // when
        double meters = GeoDistance.meters(0.0, 0.0, 0.0, 180.0);

        // then
        assertThat(meters).isCloseTo(20_015_114.442, within(0.01));
    }

    @Test
    void 위도가_범위를_벗어나면_거부한다() {
        // when, then
        assertThatThrownBy(() -> GeoDistance.meters(90.1, 127.0, 37.5, 127.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("위도");
    }

    @Test
    void 경도가_범위를_벗어나면_거부한다() {
        // when, then
        assertThatThrownBy(() -> GeoDistance.meters(37.5, 127.0, 37.5, 180.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("경도");
    }

    @Test
    void NaN_좌표를_거부한다() {
        // when, then
        assertThatThrownBy(() -> GeoDistance.meters(Double.NaN, 127.0, 37.5, 127.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
