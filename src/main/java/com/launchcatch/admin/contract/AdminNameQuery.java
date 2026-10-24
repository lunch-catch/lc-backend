package com.launchcatch.admin.contract;

import java.util.Collection;
import java.util.Map;

/*
 * 다른 도메인은 이 인터페이스를 주입받아 관리자 이름을 조회한다(#949). 화면에 "누가 했는지"를 보여 주려고
 * 관리자 ID 를 이름으로 바꾸는 용도이고, 다른 계정 정보(로그인 ID, 권한, 상태)는 내주지 않는다.
 */
public interface AdminNameQuery {

    /*
     * 관리자 ID 여러 개의 이름을 한 번의 조회로 돌려준다. 호출하는 쪽이 목록의 항목마다 부르지 않고 ID 를 모아 한 번만
     * 부르게 하려는 것이다. 삭제 표시된 관리자도 이름은 그대로 돌려주고, 행이 없는 ID 는 결과에서 빠진다. null 이나
     * 빈 입력이면 조회하지 않고 빈 맵을 돌려준다.
     */
    Map<Long, String> findNamesByIds(Collection<Long> adminIds);
}
