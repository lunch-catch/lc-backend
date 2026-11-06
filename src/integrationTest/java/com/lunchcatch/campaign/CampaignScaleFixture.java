package com.lunchcatch.campaign;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 00:00 묶음의 규모 측정(#1091)이 쓰는 캠페인 데이터를 만들고 치운다.
 *
 * 점주, 가게, 메뉴, 캠페인을 JDBC 배치 INSERT 로 커밋된 행으로 만든다. 고정한 난수(SEED)로 만들어 같은 규모로 다시
 * 만들면 같은 데이터가 나온다. 기존 통합 테스트의 준비 코드를 고치지 않고 필요한 부분만 새로 썼다.
 *
 * 만든 행은 이메일과 notice 의 접두어(PREFIX)로 찾아 지운다. 접두어로 지우므로 앞선 실행이 도중에 죽어 행이 남았어도
 * 다음 create 가 먼저 치운다.
 *
 * 캠페인 구성은 아래 비율이고 합이 규모와 같다. 규모가 100의 배수가 아니면 나머지는 RUNNING 이 갖는다.
 *
 * | 종류 | 비율 | 영업일(BUSINESS_DATE)에서의 모습 |
 * | END_ACTIVE | 15% | ACTIVE, 집행 기간이 어제 끝남 |
 * | END_SCHEDULED | 5% | SCHEDULED, 시작 전에 집행 기간이 끝남 |
 * | START_PLAIN | 10% | SCHEDULED, 오늘 시작, 같은 가게에 다른 캠페인 없음 |
 * | START_REPLACE | 10% | SCHEDULED, 오늘 시작, 같은 가게에 ACTIVE 가 이미 있음 |
 * | REPLACED_ACTIVE | 10% | START_REPLACE 와 같은 가게의 기존 ACTIVE |
 * | RUNNING | 35% | ACTIVE, 계속 진행 |
 * | PENDING_BUDGET | 10% | ACTIVE, 계속 진행, 하루 예산 변경 대기 |
 * | PAUSED | 5% | PAUSED, 계속 진행 |
 *
 * 가게는 START_REPLACE 와 REPLACED_ACTIVE 가 한 곳을 나누고 나머지는 캠페인마다 하나씩 갖는다. 가게마다 메뉴를 하나 만든다.
 * 할인 대상은 캠페인의 절반이 MENU 이고 그 가게의 메뉴를 가리킨다.
 */
public final class CampaignScaleFixture {

    public static final String PREFIX = "scale-measure-";
    public static final long SEED = 1091L;
    public static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 11, 10);

    public enum Kind {
        END_ACTIVE, END_SCHEDULED, START_PLAIN, START_REPLACE, REPLACED_ACTIVE, RUNNING, PENDING_BUDGET, PAUSED
    }

    public record CampaignRef(long campaignId, long storeId, int storeIndex, Kind kind, boolean menuDiscount) {
    }

    private record Spec(Kind kind, int storeIndex) {
    }

    private record StoreIds(long ownerId, long storeId, long menuId) {
    }

    private final JdbcTemplate jdbc;
    private int scale;
    private List<CampaignRef> campaigns = List.of();
    private List<StoreIds> stores = List.of();
    private List<CampaignRef> errorCandidates = List.of();
    private final Set<Long> errorIds = new HashSet<>();

    public CampaignScaleFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int scale() {
        return scale;
    }

    public List<CampaignRef> campaigns() {
        return campaigns;
    }

    public int storeCount() {
        return stores.size();
    }

    public int count(Kind kind) {
        return (int) campaigns.stream().filter(ref -> ref.kind() == kind).count();
    }

    // 지금 다른 가게의 메뉴를 가리키는 캠페인의 번호다.
    public Set<Long> errorCampaignIds() {
        return Set.copyOf(errorIds);
    }

    // 데이터 오류를 낼 수 있는 캠페인의 수다. 오류 비율이 이보다 큰 수를 요구하면 applyErrorRate 가 거부한다.
    public int errorCandidateCount() {
        return errorCandidates.size();
    }

    public void create(int scale) {
        if (scale < 2) {
            throw new IllegalArgumentException("규모는 2 이상이어야 한다: " + scale);
        }
        deleteAll();
        this.scale = scale;
        Random random = new Random(SEED);

        List<Spec> specs = buildSpecs(scale);
        Collections.shuffle(specs, random);
        int storeCount = specs.stream().mapToInt(Spec::storeIndex).max().orElseThrow() + 1;

        insertOwnersStoresAndMenus(storeCount);
        stores = readStores(storeCount);
        insertCampaigns(specs, random);
        campaigns = readCampaigns(specs);

        List<CampaignRef> candidates = new ArrayList<>();
        for (CampaignRef ref : campaigns) {
            if (ref.menuDiscount() && (ref.kind() == Kind.RUNNING || ref.kind() == Kind.START_PLAIN
                    || ref.kind() == Kind.START_REPLACE)) {
                candidates.add(ref);
            }
        }
        Collections.shuffle(candidates, new Random(SEED + 1));
        errorCandidates = List.copyOf(candidates);
        errorIds.clear();
    }

    /*
     * 데이터 오류 캠페인을 오류 비율에 맞게 다시 정한다. 앞서 정한 오류는 먼저 원래 메뉴로 되돌린다.
     *
     * 오류 캠페인은 RUNNING, START_PLAIN, START_REPLACE 중 MENU 할인 캠페인에서 고정한 순서로 뽑고, 할인 대상 메뉴를 다른 가게의
     * 메뉴로 바꾼다. 순서가 고정이라 0.1% 의 오류 캠페인은 1% 의 오류 캠페인에 모두 들어 있다.
     *
     * 종료 대상(END_ACTIVE, END_SCHEDULED)과 하루 예산 변경 대기(PENDING_BUDGET)에는 오류를 넣지 않는다. 이 측정이 보려는
     * 것은 가게 요약 준비와 시작 단계가 데이터 오류를 건너뛰는 경로이기 때문이다. 오류 비율은 전체 캠페인 수에 대한
     * 비율이라, 단계마다 오류가 닿는 건수는 이 단계의 대상 수와 다르다.
     */
    public void applyErrorRate(double rate) {
        int wanted = (int) Math.round(scale * rate);
        if (rate < 0 || wanted > errorCandidates.size()) {
            throw new IllegalArgumentException("오류 비율 " + rate + " 는 오류 후보 " + errorCandidates.size()
                    + "건으로 만들 수 없다(규모 " + scale + ")");
        }
        List<Object[]> restore = new ArrayList<>();
        for (CampaignRef ref : campaigns) {
            if (errorIds.contains(ref.campaignId())) {
                restore.add(new Object[] {stores.get(ref.storeIndex()).menuId(), ref.campaignId()});
            }
        }
        jdbc.batchUpdate("update campaign set target_menu_id = ? where campaign_id = ?", restore);
        errorIds.clear();

        List<Object[]> corrupt = new ArrayList<>();
        for (CampaignRef ref : errorCandidates.subList(0, wanted)) {
            long otherMenuId = stores.get((ref.storeIndex() + 1) % stores.size()).menuId();
            corrupt.add(new Object[] {otherMenuId, ref.campaignId()});
            errorIds.add(ref.campaignId());
        }
        jdbc.batchUpdate("update campaign set target_menu_id = ? where campaign_id = ?", corrupt);
    }

    // 접두어로 찾은 이 픽스처의 행을 외래 키 순서대로 지운다. 만든 적이 없어도 불러도 된다.
    public void deleteAll() {
        String ownerIds = "(select owner_id from owner where email like ?)";
        String storeIds = "(select store_id from store where owner_id in " + ownerIds + ")";
        String like = PREFIX + "%";
        jdbc.update("delete from store_campaign_summary where store_id in " + storeIds, like);
        jdbc.update("delete from campaign_status_log where campaign_id in"
                + " (select campaign_id from campaign where notice like ?)", like);
        jdbc.update("delete from campaign where notice like ?", like);
        jdbc.update("delete from store_menu where store_id in " + storeIds, like);
        jdbc.update("delete from store where owner_id in " + ownerIds, like);
        jdbc.update("delete from owner where email like ?", like);
        campaigns = List.of();
        stores = List.of();
        errorCandidates = List.of();
        errorIds.clear();
    }

    private static List<Spec> buildSpecs(int scale) {
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        counts.put(Kind.END_ACTIVE, scale * 15 / 100);
        counts.put(Kind.END_SCHEDULED, scale * 5 / 100);
        counts.put(Kind.START_PLAIN, scale * 10 / 100);
        counts.put(Kind.START_REPLACE, scale * 10 / 100);
        counts.put(Kind.REPLACED_ACTIVE, scale * 10 / 100);
        counts.put(Kind.PENDING_BUDGET, scale * 10 / 100);
        counts.put(Kind.PAUSED, scale * 5 / 100);
        int assigned = counts.values().stream().mapToInt(Integer::intValue).sum();
        counts.put(Kind.RUNNING, scale - assigned);

        List<Spec> specs = new ArrayList<>();
        int nextStore = 0;
        for (Kind kind : Kind.values()) {
            if (kind == Kind.REPLACED_ACTIVE) {
                continue;
            }
            for (int i = 0; i < counts.get(kind); i++) {
                specs.add(new Spec(kind, nextStore));
                if (kind == Kind.START_REPLACE) {
                    specs.add(new Spec(Kind.REPLACED_ACTIVE, nextStore));
                }
                nextStore++;
            }
        }
        return specs;
    }

    private void insertOwnersStoresAndMenus(int storeCount) {
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> owners = new ArrayList<>();
        for (int i = 0; i < storeCount; i++) {
            owners.add(new Object[] {email(i), "test-hash", "ACTIVE", now, now});
        }
        jdbc.batchUpdate("insert into owner (email, password_hash, status, created_at, updated_at)"
                + " values (?, ?, ?, ?, ?)", owners);

        // owner_id 의 순서가 아니라 이메일의 번호 순으로 읽어 가게 번호와 점주가 어긋나지 않게 한다.
        List<Long> ownerIds = jdbc.query(
                "select owner_id from owner where email like ?"
                        + " order by cast(substring_index(substring_index(email, '@', 1), '-', -1) as unsigned)",
                (rs, row) -> rs.getLong(1), PREFIX + "%");

        List<Object[]> storeRows = new ArrayList<>();
        for (int i = 0; i < storeCount; i++) {
            storeRows.add(new Object[] {ownerIds.get(i), PREFIX + "store-" + i, now, now, now, now});
        }
        jdbc.batchUpdate("insert into store (owner_id, name, category_code, representative_name,"
                + " business_verification_status, business_verified_at, latitude, longitude, finalized,"
                + " finalized_at, created_at, updated_at)"
                + " values (?, ?, 'KOREAN', '대표자', 'VERIFIED', ?, 37.5, 127.0, true, ?, ?, ?)", storeRows);

        jdbc.update("insert into store_menu (store_id, name, price, sort_order, created_at, updated_at)"
                        + " select s.store_id, ?, 8000, 1, ?, ? from store s"
                        + " join owner o on o.owner_id = s.owner_id where o.email like ?",
                PREFIX + "menu", now, now, PREFIX + "%");
    }

    private List<StoreIds> readStores(int storeCount) {
        List<StoreIds> result = jdbc.query(
                "select o.owner_id, s.store_id, m.store_menu_id from owner o"
                        + " join store s on s.owner_id = o.owner_id"
                        + " join store_menu m on m.store_id = s.store_id"
                        + " where o.email like ?"
                        + " order by cast(substring_index(substring_index(o.email, '@', 1), '-', -1) as unsigned)",
                (rs, row) -> new StoreIds(rs.getLong(1), rs.getLong(2), rs.getLong(3)), PREFIX + "%");
        if (result.size() != storeCount) {
            throw new IllegalStateException("가게를 " + storeCount + "개 만들었는데 " + result.size() + "개를 읽었다");
        }
        return List.copyOf(result);
    }

    private void insertCampaigns(List<Spec> specs, Random random) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate d = BUSINESS_DATE;
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            Spec spec = specs.get(i);
            StoreIds store = stores.get(spec.storeIndex());
            boolean percent = random.nextBoolean();
            int discountValue = percent ? 5 + random.nextInt(46) : 1000 + random.nextInt(4001);
            boolean menu = random.nextBoolean();
            long dailyBudget = 10_000L + random.nextInt(90_000);
            int startHour = 10 + random.nextInt(4);
            Long pending = spec.kind() == Kind.PENDING_BUDGET ? dailyBudget + 5_000L : null;
            String status = switch (spec.kind()) {
                case END_ACTIVE, REPLACED_ACTIVE, RUNNING, PENDING_BUDGET -> "ACTIVE";
                case END_SCHEDULED, START_PLAIN, START_REPLACE -> "SCHEDULED";
                case PAUSED -> "PAUSED";
            };
            LocalDate startDate = switch (spec.kind()) {
                case END_ACTIVE -> d.minusDays(30);
                case END_SCHEDULED -> d.minusDays(10);
                case START_PLAIN, START_REPLACE -> d;
                case REPLACED_ACTIVE -> d.minusDays(20);
                case RUNNING, PENDING_BUDGET -> d.minusDays(10);
                case PAUSED -> d.minusDays(5);
            };
            LocalDate endDate = switch (spec.kind()) {
                case END_ACTIVE, END_SCHEDULED -> d.minusDays(1);
                case START_PLAIN, START_REPLACE -> d.plusDays(14);
                case REPLACED_ACTIVE -> d.plusDays(10);
                case RUNNING, PENDING_BUDGET -> d.plusDays(10);
                case PAUSED -> d.plusDays(5);
            };
            rows.add(new Object[] {
                    store.ownerId(), store.storeId(), status, now,
                    status.equals("PAUSED") ? "OWNER" : null,
                    menu ? "MENU" : "ALL", percent ? "PERCENT" : "AMOUNT", discountValue,
                    menu ? store.menuId() : null,
                    100, LocalTime.of(startHour, 0), LocalTime.of(startHour + 2, 30),
                    "ALL", "AGE_20S,AGE_30S",
                    dailyBudget, pending, startDate, endDate, PREFIX + i, now, now});
        }
        jdbc.batchUpdate("insert into campaign (owner_id, store_id, status, status_changed_at, paused_reason,"
                + " discount_target_type, discount_type, discount_value, target_menu_id, issue_quantity,"
                + " usable_start_time, usable_end_time, target_gender, target_age_groups,"
                + " daily_budget, pending_daily_budget, start_date, end_date, notice, created_at, updated_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
    }

    private List<CampaignRef> readCampaigns(List<Spec> specs) {
        List<Object[]> read = jdbc.query(
                "select campaign_id, notice, discount_target_type from campaign where notice like ?",
                (rs, row) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3)}, PREFIX + "%");
        if (read.size() != specs.size()) {
            throw new IllegalStateException("캠페인을 " + specs.size() + "건 만들었는데 " + read.size() + "건을 읽었다");
        }
        CampaignRef[] byIndex = new CampaignRef[specs.size()];
        for (Object[] row : read) {
            int index = Integer.parseInt(((String) row[1]).substring(PREFIX.length()));
            Spec spec = specs.get(index);
            byIndex[index] = new CampaignRef((Long) row[0], stores.get(spec.storeIndex()).storeId(),
                    spec.storeIndex(), spec.kind(), "MENU".equals(row[2]));
        }
        return List.of(byIndex);
    }

    private static String email(int storeIndex) {
        return PREFIX + storeIndex + "@example.com";
    }
}
