package com.launchcatch.coupon;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CouponQrImageEncoder {

    // Todo: qr코드 사이즈 협의
    private static final int IMAGE_SIZE = 300;

    public String encode(UUID qrToken) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            BitMatrix matrix = new QRCodeWriter().encode(
                    qrToken.toString(), BarcodeFormat.QR_CODE, IMAGE_SIZE, IMAGE_SIZE,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (WriterException | IOException exception) {
            throw new IllegalStateException("쿠폰 QR 이미지를 생성할 수 없습니다.", exception);
        }
    }
}
