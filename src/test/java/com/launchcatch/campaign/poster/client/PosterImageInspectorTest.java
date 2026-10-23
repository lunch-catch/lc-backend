package com.launchcatch.campaign.poster.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.BoundingBox;
import software.amazon.awssdk.services.rekognition.model.DetectTextRequest;
import software.amazon.awssdk.services.rekognition.model.DetectTextResponse;
import software.amazon.awssdk.services.rekognition.model.Geometry;
import software.amazon.awssdk.services.rekognition.model.RekognitionException;
import software.amazon.awssdk.services.rekognition.model.TextDetection;
import software.amazon.awssdk.services.rekognition.model.TextTypes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

@ExtendWith(MockitoExtension.class)
class PosterImageInspectorTest {

    private static final String OBJECT_KEY = "posters/1/image.png";

    @Mock
    private S3Client s3Client;

    @Mock
    private RekognitionClient rekognitionClient;

    private PosterImageInspector inspector;

    @BeforeEach
    void setUp() {
        inspector = new PosterImageInspector(s3Client, rekognitionClient, "media-bucket");
    }

    @Test
    @DisplayName("이미지 크기와 글자 줄 상자의 면적 비율 합, 찾은 글자를 돌려준다")
    void 크기와_글자_비율을_잰다() throws IOException {
        givenImage(png(300, 200));
        when(rekognitionClient.detectText(any(DetectTextRequest.class))).thenReturn(DetectTextResponse.builder()
                .textDetections(
                        line("오늘만 할인", 0.5f, 0.2f),
                        line("김밥천국", 0.5f, 0.2f),
                        TextDetection.builder().type(TextTypes.WORD).detectedText("오늘만")
                                .geometry(geometry(0.9f, 0.9f)).build())
                .build());

        PosterImageInspection result = inspector.inspect(OBJECT_KEY);

        assertThat(result.status()).isEqualTo(PosterImageInspection.Status.READ);
        assertThat(result.width()).isEqualTo(300);
        assertThat(result.height()).isEqualTo(200);
        assertThat(result.textRatio()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(result.detectedTexts()).containsExactly("오늘만 할인", "김밥천국");
    }

    @Test
    @DisplayName("디코딩할 수 없는 이미지는 Rekognition 을 부르지 않고 UNREADABLE 이다")
    void 읽을_수_없는_이미지() {
        givenImage("not an image".getBytes());

        PosterImageInspection result = inspector.inspect(OBJECT_KEY);

        assertThat(result.status()).isEqualTo(PosterImageInspection.Status.UNREADABLE);
        verify(rekognitionClient, never()).detectText(any(DetectTextRequest.class));
    }

    @Test
    @DisplayName("S3 읽기가 실패하면 TOOL_FAILURE 다")
    void S3_실패() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("boom").build());

        assertThat(inspector.inspect(OBJECT_KEY).status()).isEqualTo(PosterImageInspection.Status.TOOL_FAILURE);
    }

    @Test
    @DisplayName("Rekognition 이 실패하면 TOOL_FAILURE 다")
    void Rekognition_실패() throws IOException {
        givenImage(png(300, 300));
        when(rekognitionClient.detectText(any(DetectTextRequest.class)))
                .thenThrow(RekognitionException.builder().statusCode(500).message("boom").build());

        assertThat(inspector.inspect(OBJECT_KEY).status()).isEqualTo(PosterImageInspection.Status.TOOL_FAILURE);
    }

    @Test
    @DisplayName("Rekognition 이 시간을 넘기면 TOOL_FAILURE 다")
    void Rekognition_타임아웃() throws IOException {
        givenImage(png(300, 300));
        when(rekognitionClient.detectText(any(DetectTextRequest.class)))
                .thenThrow(ApiCallTimeoutException.builder().message("timeout").build());

        assertThat(inspector.inspect(OBJECT_KEY).status()).isEqualTo(PosterImageInspection.Status.TOOL_FAILURE);
    }

    private void givenImage(byte[] bytes) {
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().build(), AbortableInputStream.create(new ByteArrayInputStream(bytes))));
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static TextDetection line(String text, float width, float height) {
        return TextDetection.builder().type(TextTypes.LINE).detectedText(text).geometry(geometry(width, height)).build();
    }

    private static Geometry geometry(float width, float height) {
        return Geometry.builder().boundingBox(BoundingBox.builder().width(width).height(height).build()).build();
    }
}
