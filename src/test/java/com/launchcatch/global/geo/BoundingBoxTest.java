package com.launchcatch.global.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/*
 * 기대값은 운영 코드의 수식을 다시 쓰지 않고 바깥에서 따로 계산해 상수로 박았다.
 * 테스트가 같은 수식을 다시 계산하면 그 수식이 틀려도 함께 틀려서 통과한다.
 */
class BoundingBoxTest {

    // 서울시청
    private static final double LATITUDE = 37.5663;
    private static final double LONGITUDE = 126.9779;

    private static final double RADIUS_METERS = 500;

    // 위 중심과 반경으로 나와야 하는 네 경계
    private static final double EXPECTED_MIN_LATITUDE = 37.5618033982;
    private static final double EXPECTED_MAX_LATITUDE = 37.5707966018;
    private static final double EXPECTED_MIN_LONGITUDE = 126.9722267741;
    private static final double EXPECTED_MAX_LONGITUDE = 126.9835732259;

    private static final double TOLERANCE = 0.0000000001;

    /*
     * 경계를 바로 안쪽과 바로 바깥쪽에서 찌르는 좌표다. 위 네 상수를 그대로 쓰면
     * 반올림 때문에 참값보다 커지거나 작아져서 어느 쪽인지 단정할 수 없다.
     * 소수 5자리는 1m 남짓이라 경계 판정을 확인하기에 충분하다.
     */
    private static final double JUST_INSIDE_MAX_LATITUDE = 37.57079;
    private static final double JUST_OUTSIDE_MAX_LATITUDE = 37.57080;
    private static final double JUST_INSIDE_MAX_LONGITUDE = 126.98357;
    private static final double JUST_OUTSIDE_MAX_LONGITUDE = 126.98358;

    @Test
    void 반경_500m_상자의_네_경계를_계산한다() {
        // when
        BoundingBox box = BoundingBox.around(LATITUDE, LONGITUDE, RADIUS_METERS);

        // then
        assertThat(box.minLatitude()).isCloseTo(EXPECTED_MIN_LATITUDE, within(TOLERANCE));
        assertThat(box.maxLatitude()).isCloseTo(EXPECTED_MAX_LATITUDE, within(TOLERANCE));
        assertThat(box.minLongitude()).isCloseTo(EXPECTED_MIN_LONGITUDE, within(TOLERANCE));
        assertThat(box.maxLongitude()).isCloseTo(EXPECTED_MAX_LONGITUDE, within(TOLERANCE));
    }

    @Test
    void 중심은_상자_안에_있다() {
        // given
        BoundingBox box = BoundingBox.around(LATITUDE, LONGITUDE, RADIUS_METERS);

        // when, then
        assertThat(box.contains(LATITUDE, LONGITUDE)).isTrue();
    }

    @Test
    void 정북쪽_경계_바로_안쪽을_담는다() {
        // given
        BoundingBox box = BoundingBox.around(LATITUDE, LONGITUDE, RADIUS_METERS);

        // when, then
        assertThat(box.contains(JUST_INSIDE_MAX_LATITUDE, LONGITUDE)).isTrue();
    }

    @Test
    void 정북쪽_경계_바로_바깥은_담지_않는다() {
        // given
        BoundingBox box = BoundingBox.around(LATITUDE, LONGITUDE, RADIUS_METERS);

        // when, then
        assertThat(box.contains(JUST_OUTSIDE_MAX_LATITUDE, LONGITUDE)).isFalse();
    }

    @Test
    void 정동쪽_경계_바로_안쪽을_담는다() {
        // given
        BoundingBox box = BoundingBox.around(LATITUDE, LONGITUDE, RADIUS_METERS);

        // when, then
        assertThat(box.contains(LATITUDE, JUST_INSIDE_MAX_LONGITUDE)).isTrue();
    }

    @Test
    void 정동쪽_경계_바로_바깥은_담지_않는다() {
        // given
        BoundingBox box = BoundingBox.around(LATITUDE, LONGITUDE, RADIUS_METERS);

        // when, then
        assertThat(box.contains(LATITUDE, JUST_OUTSIDE_MAX_LONGITUDE)).isFalse();
    }

    @Test
    void 북동_모서리_안쪽도_담는다() {
        // given
        BoundingBox box = BoundingBox.around(LATITUDE, LONGITUDE, RADIUS_METERS);

        // when, then
        assertThat(box.contains(JUST_INSIDE_MAX_LATITUDE, JUST_INSIDE_MAX_LONGITUDE)).isTrue();
    }

    @Test
    void 고위도에서는_경도_폭이_위도_폭보다_넓다() {
        // when
        BoundingBox box = BoundingBox.around(LATITUDE, LONGITUDE, RADIUS_METERS);

        // then
        double latitudeSpan = box.maxLatitude() - box.minLatitude();
        double longitudeSpan = box.maxLongitude() - box.minLongitude();
        assertThat(longitudeSpan).isGreaterThan(latitudeSpan);
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

    @Test
    void 위도와_경도를_맞바꿔_넣으면_범위_검사에_걸린다() {
        // when, then
        assertThatThrownBy(() -> new BoundingBox(LONGITUDE, LONGITUDE, LATITUDE, LATITUDE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("위도");
    }
}
