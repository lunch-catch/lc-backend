package com.launchcatch.global.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class BoundingBoxTest {

    // 서울시청
    private static final double LATITUDE = 37.5663;
    private static final double LONGITUDE = 126.9779;

    private static final double RADIUS_METERS = 500;

    @Test
    void 중심은_상자_안에_있다() {
        // given
        BoundingBox box = BoundingBox.around(LATITUDE, LONGITUDE, RADIUS_METERS);

        // when, then
        assertThat(box.contains(LATITUDE, LONGITUDE)).isTrue();
    }

    @Test
    void 정북쪽_반경_경계점을_담는다() {
        // given
        BoundingBox box = BoundingBox.around(LATITUDE, LONGITUDE, RADIUS_METERS);
        double northEdge = LATITUDE + Math.toDegrees(RADIUS_METERS / GeoDistance.EARTH_RADIUS_METERS);

        // when, then
        assertThat(box.contains(northEdge, LONGITUDE)).isTrue();
    }

    @Test
    void 정동쪽_반경_경계점을_담는다() {
        // given
        BoundingBox box = BoundingBox.around(LATITUDE, LONGITUDE, RADIUS_METERS);
        double eastEdge = LONGITUDE + Math.toDegrees(
                RADIUS_METERS / (GeoDistance.EARTH_RADIUS_METERS * Math.cos(Math.toRadians(LATITUDE))));

        // when, then
        assertThat(box.contains(LATITUDE, eastEdge)).isTrue();
    }

    @Test
    void 반경_바깥의_점은_담지_않는다() {
        // given
        BoundingBox box = BoundingBox.around(LATITUDE, LONGITUDE, RADIUS_METERS);

        // when, then
        assertThat(box.contains(LATITUDE + 0.1, LONGITUDE)).isFalse();
    }

    @Test
    void 경도_폭은_위도_폭보다_넓다() {
        // when
        BoundingBox box = BoundingBox.around(LATITUDE, LONGITUDE, RADIUS_METERS);

        // then
        double latitudeSpan = box.maxLatitude() - box.minLatitude();
        double longitudeSpan = box.maxLongitude() - box.minLongitude();
        assertThat(longitudeSpan).isGreaterThan(latitudeSpan);
    }

    @Test
    void 반경_500m_상자의_위도_폭은_약_0_009도다() {
        // when
        BoundingBox box = BoundingBox.around(LATITUDE, LONGITUDE, RADIUS_METERS);

        // then
        assertThat(box.maxLatitude() - box.minLatitude()).isCloseTo(0.008993, within(0.000001));
    }

    @Test
    void 반경이_0이면_중심만_담는_상자가_된다() {
        // when
        BoundingBox box = BoundingBox.around(LATITUDE, LONGITUDE, 0);

        // then
        assertThat(box.minLatitude()).isEqualTo(box.maxLatitude());
        assertThat(box.minLongitude()).isEqualTo(box.maxLongitude());
    }

    @Test
    void 자정경선에_걸치면_경도를_전체로_넓힌다() {
        // when
        BoundingBox box = BoundingBox.around(37.5, 179.99, 10_000);

        // then
        assertThat(box.minLongitude()).isEqualTo(-180.0);
        assertThat(box.maxLongitude()).isEqualTo(180.0);
    }

    @Test
    void 극에_가까우면_경도를_전체로_넓힌다() {
        // when
        BoundingBox box = BoundingBox.around(89.9, 127.0, 1_000);

        // then
        assertThat(box.minLongitude()).isEqualTo(-180.0);
        assertThat(box.maxLongitude()).isEqualTo(180.0);
    }

    @Test
    void 극을_넘는_위도는_90도에서_멈춘다() {
        // when
        BoundingBox box = BoundingBox.around(89.99, 127.0, 100_000);

        // then
        assertThat(box.maxLatitude()).isEqualTo(90.0);
    }

    @Test
    void 음수_반경을_거부한다() {
        // when, then
        assertThatThrownBy(() -> BoundingBox.around(LATITUDE, LONGITUDE, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("반경");
    }

    @Test
    void 위도_하한이_상한보다_크면_거부한다() {
        // when, then
        assertThatThrownBy(() -> new BoundingBox(38.0, 37.0, 126.0, 127.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("위도 하한");
    }

    @Test
    void 경도_하한이_상한보다_크면_거부한다() {
        // when, then
        assertThatThrownBy(() -> new BoundingBox(37.0, 38.0, 127.0, 126.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("경도 하한");
    }
}
