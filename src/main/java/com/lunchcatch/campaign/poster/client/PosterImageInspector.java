package com.lunchcatch.campaign.poster.client;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.BoundingBox;
import software.amazon.awssdk.services.rekognition.model.DetectTextRequest;
import software.amazon.awssdk.services.rekognition.model.DetectTextResponse;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.InvalidImageFormatException;
import software.amazon.awssdk.services.rekognition.model.S3Object;
import software.amazon.awssdk.services.rekognition.model.TextDetection;
import software.amazon.awssdk.services.rekognition.model.TextTypes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/*
 * 포스터 이미지의 크기와 이미지 속 글자를 잰다(명세서 57행, #535).
 * 외부 호출이 실패하거나 시간을 넘기면 예외를 올리지 않고 TOOL_FAILURE 로 돌려준다. 명세서가
 * 검수 도구 장애를 FAIL 로 정해서, 검수 서비스가 그 결과를 그대로 FAIL 사유로 옮기면 된다.
 *
 * 크기는 S3 에서 받은 스트림의 헤더만 읽어 구한다. 전체를 디코딩하지 않아 10MB 이미지도
 * 앞부분만 읽고 닫는다. 디코딩하지 못하면(헤더는 PNG 인데 실제는 다른 형식 등) Rekognition 을
 * 부르지 않고 UNREADABLE 로 끝낸다. Rekognition 도 같은 이미지를 InvalidImageFormatException 으로
 * 거부할 것이라 부를 이유가 없다.
 */
@Slf4j
@Component
public class PosterImageInspector {

    private final S3Client s3Client;
    private final RekognitionClient rekognitionClient;
    private final String mediaBucket;

    public PosterImageInspector(
            S3Client s3Client, RekognitionClient rekognitionClient, @Value("${s3.media-bucket}") String mediaBucket) {
        this.s3Client = s3Client;
        this.rekognitionClient = rekognitionClient;
        this.mediaBucket = mediaBucket;
    }

    public PosterImageInspection inspect(String objectKey) {
        int[] size;
        try {
            size = readSize(objectKey);
        } catch (SdkException e) {
            log.warn("event=POSTER_IMAGE_READ_FAILED objectKey={}", objectKey, e);
            return PosterImageInspection.toolFailure();
        }
        if (size == null) {
            return PosterImageInspection.unreadable();
        }
        try {
            DetectTextResponse response = rekognitionClient.detectText(DetectTextRequest.builder()
                    .image(Image.builder()
                            .s3Object(S3Object.builder().bucket(mediaBucket).name(objectKey).build())
                            .build())
                    .build());
            List<TextDetection> lines = response.textDetections().stream()
                    .filter(detection -> detection.type() == TextTypes.LINE)
                    .toList();
            return PosterImageInspection.read(size[0], size[1], textRatio(lines),
                    lines.stream().map(TextDetection::detectedText).toList());
        } catch (InvalidImageFormatException e) {
            return PosterImageInspection.unreadable();
        } catch (SdkException e) {
            log.warn("event=POSTER_IMAGE_TEXT_DETECTION_FAILED objectKey={}", objectKey, e);
            return PosterImageInspection.toolFailure();
        }
    }

    /*
     * 읽지 못하는 형식이면 null 이다.
     * S3 스트림을 읽다가 난 오류는 SdkException 으로 올려 TOOL_FAILURE 로 가게 하고, 디코딩
     * 자체가 안 되는 오류만 null 로 돌린다.
     */
    private int[] readSize(String objectKey) {
        try (InputStream raw = s3Client.getObject(GetObjectRequest.builder()
                        .bucket(mediaBucket)
                        .key(objectKey)
                        .build());
                InputStream in = new S3StreamFailureTranslator(raw, objectKey);
                ImageInputStream imageIn = ImageIO.createImageInputStream(in)) {
            if (imageIn == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageIn);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageIn, true, true);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            log.info("event=POSTER_IMAGE_DECODE_FAILED reason=UNDECODABLE objectKey={}", objectKey, e);
            return null;
        }
    }

    /*
     * S3 에서 받은 스트림을 읽다가 난 IOException 을 SdkClientException 으로 바꿔 던진다.
     *
     * 감싸지 않으면 ImageIO 가 그 오류를 "디코딩 못 함" 으로 읽어 IMAGE_UNREADABLE 이 되고,
     * S3 쪽 장애가 점주가 고칠 수 있는 원인으로 잘못 기록된다. SdkClientException 은
     * RuntimeException 이라 여기서 던지면 readSize 의 IOException 처리를 타지 않고
     * inspect() 의 SdkException 처리로 그대로 올라간다.
     */
    private static final class S3StreamFailureTranslator extends FilterInputStream {

        private final String objectKey;

        S3StreamFailureTranslator(InputStream in, String objectKey) {
            super(in);
            this.objectKey = objectKey;
        }

        @Override
        public int read() throws IOException {
            try {
                return super.read();
            } catch (IOException e) {
                throw translate(e);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return super.read(b, off, len);
            } catch (IOException e) {
                throw translate(e);
            }
        }

        private SdkClientException translate(IOException e) {
            return SdkClientException.create("poster image stream read failed: key=" + objectKey, e);
        }
    }

    /*
     * 글자 줄 상자는 이미지 크기에 대한 비율 좌표라 넓이를 그대로 더한다.
     * 상자가 겹치면 1을 넘을 수 있어 1로 자른다.
     */
    private static double textRatio(List<TextDetection> lines) {
        double sum = 0;
        for (TextDetection line : lines) {
            BoundingBox box = line.geometry() == null ? null : line.geometry().boundingBox();
            if (box != null && box.width() != null && box.height() != null) {
                sum += box.width() * box.height();
            }
        }
        return Math.min(sum, 1.0);
    }
}
