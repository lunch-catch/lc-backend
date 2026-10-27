package com.launchcatch.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class CouponQrImageEncoderTest {

    @Test
    void 설정된_크기로_QR_PNG를_생성한다() throws Exception {
        var properties = CouponTestProperties.with(Map.of("coupon.qr.image-size", 240));
        String encoded = new CouponQrImageEncoder(properties).encode(UUID.randomUUID());
        var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
        assertThat(image.getWidth()).isEqualTo(240);
        assertThat(image.getHeight()).isEqualTo(240);
    }
}
