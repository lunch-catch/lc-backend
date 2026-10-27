package com.lunchcatch.campaign.poster.client;

import java.util.List;

/*
 * 포스터 이미지를 읽고 글자를 찾은 결과다. status 가 READ 일 때만 크기와 글자 값이 뜻을 갖는다.
 * textRatio 는 글자 줄 상자의 면적 비율 합이다(0 이상 1 이하).
 */
public record PosterImageInspection(
        Status status, int width, int height, double textRatio, List<String> detectedTexts) {

    public enum Status {
        READ,
        UNREADABLE,
        TOOL_FAILURE
    }

    public static PosterImageInspection read(int width, int height, double textRatio, List<String> detectedTexts) {
        return new PosterImageInspection(Status.READ, width, height, textRatio, List.copyOf(detectedTexts));
    }

    public static PosterImageInspection unreadable() {
        return new PosterImageInspection(Status.UNREADABLE, 0, 0, 0, List.of());
    }

    public static PosterImageInspection toolFailure() {
        return new PosterImageInspection(Status.TOOL_FAILURE, 0, 0, 0, List.of());
    }
}
