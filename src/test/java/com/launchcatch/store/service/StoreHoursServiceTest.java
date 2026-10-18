package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.launchcatch.store.contract.OwnerStoreOverview.TermsAgreement;
import com.launchcatch.store.contract.OwnerStoreSnapshot;
import com.launchcatch.store.contract.OwnerStoreSnapshot.BusinessHour;
import com.launchcatch.store.contract.StoreHoursSaveCommand;
import com.launchcatch.store.dto.StoreRegistrationProgressSnapshot;
import com.launchcatch.store.entity.StoreRegistrationProgress;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.OwnerStoreQueryRepository;
import com.launchcatch.store.repository.StoreBusinessHourRepository;
import com.launchcatch.store.repository.StoreRegistrationProgressRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;

class StoreHoursServiceTest {
    private final StoreRegistrationProgressRepository progresses = mock(StoreRegistrationProgressRepository.class);
    private final OwnerStoreQueryRepository stores = mock(OwnerStoreQueryRepository.class);
    private final StoreBusinessHourRepository hours = mock(StoreBusinessHourRepository.class);
    private final StoreRegistrationProgressService registration = mock(StoreRegistrationProgressService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final StoreHoursService service = new StoreHoursService(progresses, stores, hours, registration, clock);
    private final List<BusinessHour> week = closedWeek();

    private StoreRegistrationProgress progress(boolean later) {
        var progress = StoreRegistrationProgress.start(1L);
        progress.completeStep(0, StoreRegistrationStep.TERMS, null);
        progress.completeStep(1, StoreRegistrationStep.STORE_INFO, 10L);
        progress.completeStep(2, StoreRegistrationStep.LOCATION, null);
        if (later) progress.completeStep(3, StoreRegistrationStep.HOURS, null);
        when(progresses.findByOwnerIdForUpdate(1L)).thenReturn(Optional.of(progress));
        return progress;
    }

    private void store(boolean finalized, String location) {
        when(stores.findByOwnerId(1L)).thenReturn(Optional.of(new OwnerStoreSnapshot(10L, finalized,
                "가게", "KOREAN", "점주", "1234567890", "02-123-4567", "VERIFIED", LocalDateTime.now(clock),
                location, "도로명 주소", BigDecimal.valueOf(37.5), BigDecimal.valueOf(127), List.of(), null, List.of(), List.of())));
        when(stores.findTermsAgreement(1L)).thenReturn(Optional.of(new TermsAgreement(null, null, true, true,
                LocalDateTime.now(clock), LocalDateTime.now(clock), null)));
    }

    @Test
    void 최초_저장은_시간표와_진행_상태를_함께_반영한다() {
        progress(false);
        store(false, "123");
        when(registration.completeStep(1L, 3, StoreRegistrationStep.HOURS, null))
                .thenReturn(new StoreRegistrationProgressSnapshot(10L, StoreRegistrationStep.IMAGES, 4));
        var result = service.saveOnboarding(1L, new StoreHoursSaveCommand(3L, week));
        assertThat(result.storeId()).isEqualTo(10L);
        assertThat(result.resumeStep()).isEqualTo("IMAGES");
        assertThat(result.version()).isEqualTo(4);
        verify(hours).replace(10L, week, LocalDateTime.now(clock));
    }

    @Test
    void 완료한_단계_수정은_이후_재개_단계를_유지한다() {
        progress(true);
        store(false, "123");
        when(registration.reviseStep(1L, 4, StoreRegistrationStep.HOURS))
                .thenReturn(new StoreRegistrationProgressSnapshot(10L, StoreRegistrationStep.IMAGES, 5));
        var result = service.saveOnboarding(1L, new StoreHoursSaveCommand(4L, week));
        assertThat(result.resumeStep()).isEqualTo("IMAGES");
        assertThat(result.version()).isEqualTo(5);
    }

    @Test
    void 위치가_없으면_저장된_진행_단계가_HOURS여도_거절한다() {
        progress(false);
        store(false, null);
        assertThatThrownBy(() -> service.saveOnboarding(1L, new StoreHoursSaveCommand(3L, week)))
                .isInstanceOf(StoreException.class).extracting(e -> ((StoreException) e).getErrorCode().getCode()).isEqualTo("STORE-002");
        verifyNoInteractions(hours);
    }

    @Test
    void 진행_상태가_있어도_본인_가게가_없으면_저장하지_않는다() {
        progress(false);
        when(stores.findByOwnerId(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.saveOnboarding(1L, new StoreHoursSaveCommand(3L, week)))
                .isInstanceOf(StoreException.class)
                .extracting(e -> ((StoreException) e).getErrorCode().getCode()).isEqualTo("STORE-004");
        verifyNoInteractions(hours, registration);
    }

    @Test
    void 오래된_버전이면_기존_시간표를_수정하지_않는다() {
        progress(false);
        assertThatThrownBy(() -> service.saveOnboarding(1L, new StoreHoursSaveCommand(2L, week)))
                .isInstanceOf(StoreException.class).extracting(e -> ((StoreException) e).getErrorCode().getCode()).isEqualTo("STORE-001");
        verifyNoInteractions(hours);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ACTIVE는_최종_등록된_가게만_수정하고_진행_상태에_접근하지_않는다(boolean finalized) {
        store(finalized, "123");
        if (finalized) {
            var result = service.saveActive(1L, new StoreHoursSaveCommand(null, week));
            assertThat(result.storeId()).isEqualTo(10L);
            assertThat(result.resumeStep()).isNull();
            assertThat(result.version()).isNull();
            verify(hours).replace(10L, week, LocalDateTime.now(clock));
        } else {
            assertThatThrownBy(() -> service.saveActive(1L, new StoreHoursSaveCommand(null, week))).isInstanceOf(StoreException.class);
            verifyNoInteractions(hours);
        }
        verifyNoInteractions(progresses, registration);
    }

    @Test
    void 잘못된_시간표와_상태에_맞지_않는_버전은_저장하지_않는다() {
        assertThatThrownBy(() -> service.saveOnboarding(1L, new StoreHoursSaveCommand(3L, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveOnboarding(1L, new StoreHoursSaveCommand(null, week))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveActive(1L, new StoreHoursSaveCommand(3L, week))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(hours);
    }

    @Test
    void DB_장애는_성공으로_바꾸지_않는다() {
        store(true, "123");
        doThrow(new DataAccessResourceFailureException("offline")).when(hours).replace(anyLong(), anyList(), any());
        assertThatThrownBy(() -> service.saveActive(1L, new StoreHoursSaveCommand(null, week)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void 영업일만_보내면_나머지_요일은_휴무로_채워_온보딩을_완료한다() {
        progress(false);
        store(false, "123");
        var monday = monday("09:00", "21:00", null, null, null);
        when(registration.completeStep(1L, 3, StoreRegistrationStep.HOURS, null))
                .thenReturn(new StoreRegistrationProgressSnapshot(10L, StoreRegistrationStep.IMAGES, 4));

        var result = service.saveOnboarding(1L, new StoreHoursSaveCommand(3L, List.of(monday)));

        var expected = new ArrayList<>(week);
        expected.set(0, monday);
        verify(hours).replace(10L, expected, LocalDateTime.now(clock));
        assertThat(result.resumeStep()).isEqualTo("IMAGES");
        assertThat(result.version()).isEqualTo(4);
    }

    @Test
    void ACTIVE에서_빈_배열은_일주일_전체를_휴무로_교체한다() {
        store(true, "123");

        service.saveActive(1L, new StoreHoursSaveCommand(null, List.of()));

        verify(hours).replace(10L, week, LocalDateTime.now(clock));
        verifyNoInteractions(progresses, registration);
    }

    private static List<BusinessHour> closedWeek() {
        return Arrays.stream(DayOfWeek.values()).map(day -> new BusinessHour(day.name(), null, null, true,
                null, null, null)).toList();
    }

    private static BusinessHour monday(String open, String close, String start, String end, String last) {
        return new BusinessHour("MONDAY", time(open), time(close), false, time(start), time(end), time(last));
    }

    private static LocalTime time(String value) {
        return value == null ? null : LocalTime.parse(value);
    }

    @Test
    void 당일_영업과_브레이크_종료_이후_라스트오더를_저장한다() {
        store(true, "123");
        var input = new ArrayList<>(week);
        input.set(0, monday("09:00", "21:00", "14:00", "15:00", "20:30"));

        var result = service.saveActive(1L, new StoreHoursSaveCommand(null, input));

        assertThat(result.storeId()).isEqualTo(10L);
        verify(hours).replace(10L, input, LocalDateTime.now(clock));
    }

    @ParameterizedTest
    @MethodSource("invalidHours")
    void 잘못된_시간표는_서비스가_저장하지_않는다(List<BusinessHour> input) {
        assertThatThrownBy(() -> service.saveActive(1L, new StoreHoursSaveCommand(null, input)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(hours, registration);
    }

    static Stream<List<BusinessHour>> invalidHours() {
        var duplicate = new ArrayList<>(closedWeek());
        duplicate.set(6, closedWeek().getFirst());
        var invalid = Stream.of(
                monday(null, "21:00", null, null, null),
                monday("09:00", null, null, null, null),
                monday("21:00", "09:00", null, null, null),
                monday("09:00", "09:00", null, null, null),
                monday("09:00:00.001", "21:00", null, null, null),
                monday("09:00", "21:00", "14:00", null, null),
                monday("09:00", "21:00", null, "15:00", null),
                monday("09:00", "21:00", "15:00", "14:00", null),
                monday("09:00", "21:00", "09:00", "15:00", null),
                monday("09:00", "21:00", "14:00", "21:00", null),
                monday("09:00", "21:00", null, null, "08:59"),
                monday("09:00", "21:00", null, null, "21:01"),
                monday("09:00", "21:00", "14:00", "15:00", "14:30"),
                new BusinessHour("MONDAY", null, null, true, null, null, time("18:00")),
                new BusinessHour("MONDAY", time("09:00"), time("21:00"), true, null, null, null),
                new BusinessHour("INVALID", null, null, true, null, null, null),
                new BusinessHour(null, null, null, true, null, null, null));
        return Stream.concat(Stream.of(null, duplicate), invalid.map(hour -> {
            var hours = new ArrayList<>(closedWeek());
            hours.set(0, hour);
            return hours;
        }));
    }
}
