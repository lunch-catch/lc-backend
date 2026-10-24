/*
 * 템플릿과 포스터다 (#858). 받은 템플릿 디자인으로 바꿀 수 있게 기본 세계(V4)에서 떼어 냈다.
 * 내용이나 자리표시자 값이 바뀌면 다시 돈다. 다시 돌 때 기본 세계와 시연 캠페인의 포스터를 지우고 지금의 템플릿으로 다시 만든다.
 * 템플릿 B + 1 ~ B + 14 는 상태(게시 활성, 게시 비활성, 작성 중, 버전 25개)를 시나리오대로 두고 이름과 HTML 을 다시 쓴다. 실제 점주 포스터가 가리켜도 지우지 않는다.
 * 포스터는 업종에 맞는 스타일과 할인 방식(전체할인, 메뉴할인)으로 템플릿을 고른다. 포스터 사진(poster.jpg)은 받은 이미지로 같은 키에 덮어쓴다.
 * 캠페인 값은 campaign, store, store_menu 행을 읽어 만든다. 기본 세계(V4)와 시연 층(R__demo) 뒤에 돈다.
 */

/*
 * 1. 지우기: 기본 세계(1,000만) 캠페인과 시연 캠페인 7개의 포스터와 검수 결과, 예전 시연 템플릿 "시연 기본형".
 * 시연은 대역이 아니라 R__demo 의 캠페인 번호로 고른다. 실제로 만든 캠페인도 자동 증가로 2,000만 대역 번호를 받기 때문이다.
 */
DELETE FROM poster_moderation_result
WHERE poster_id IN (SELECT poster_id FROM poster WHERE campaign_id BETWEEN 10000001 AND 19999999 OR campaign_id IN (20000101, 20000111, 20000112, 20000113, 20000301, 20000401, 20000501));
DELETE FROM poster WHERE campaign_id BETWEEN 10000001 AND 19999999 OR campaign_id IN (20000101, 20000111, 20000112, 20000113, 20000301, 20000401, 20000501);
DELETE FROM template_version
WHERE template_id = 20000001 AND NOT EXISTS (SELECT 1 FROM poster p WHERE p.template_id = 20000001);
DELETE FROM template
WHERE template_id = 20000001 AND NOT EXISTS (SELECT 1 FROM poster p WHERE p.template_id = 20000001);
DELETE FROM template_version WHERE template_id BETWEEN 10000001 AND 10000014;

/*
 * 2. 템플릿 HTML (#886).
 * 1 ~ 10 은 선착순 쿠폰 전단지 10종이다. 스타일 5(한상, 홍등, 와, 비스트로, 팝) x 할인 방식 2(전체할인, 메뉴할인)이고 t = 스타일 * 2 + 방식 + 1 이다.
 * 11 ~ 14 는 시나리오용(작성 중 2, 게시 중지 2)이라 포스터에 쓰지 않고 1, 2, 9, 10 의 HTML 을 그대로 쓴다.
 * data-slot 칸 넷(eventName, discount, period, image)이 하나씩 있고 허용 태그(div, span, p, h1, h2, h3, strong, em, br, img)와 인라인 스타일만 쓴다.
 * 템플릿 생성기(BedrockTemplateHtmlGenerator)의 필수 규칙과 디자인 지침을 모두 따른다. 슬롯 넷 말고 글자를 넣지 않고, 글꼴은 sans-serif 기본값만 쓴다.
 * 글자 크기와 여백도 그 지침 안이다. 점주 미리보기와 피드는 폭 360px 로 그린 뒤 줄인다.
 */
CREATE TEMPORARY TABLE tpl_html (t INT PRIMARY KEY, name VARCHAR(100) NOT NULL, html TEXT NOT NULL)
    DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO tpl_html (t, name, html) VALUES
(1, '선착순 쿠폰 전단지 한상 전체할인',
 '<div style="display:flex;flex-direction:column;width:100%;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;padding:28px 24px;background:linear-gradient(160deg,#FFF6E9,#F4D9B8);color:#3A2215;font-family:sans-serif;text-align:center;border-radius:24px"><div style="align-self:center;width:56px;height:6px;border-radius:12px;background-color:#B23A1E;margin-bottom:14px"></div><h1 data-slot="eventName" style="margin:0 0 14px;font-size:34px;line-height:1.25;font-weight:800;word-break:keep-all;color:#3A2215">이벤트명</h1><img data-slot="image" alt="메뉴 이미지" style="display:block;flex:1;min-height:0;width:100%;object-fit:cover;border-radius:16px;background-color:rgba(255,255,255,0.75);border:3px solid #B23A1E;margin-bottom:16px"><div style="background-color:#B23A1E;border-radius:16px;padding:14px 12px;margin-bottom:14px"><p data-slot="discount" style="margin:0;font-size:28px;line-height:1.3;font-weight:800;word-break:keep-all;color:#FFF6E9">할인 내용</p></div><div><p data-slot="period" style="margin:0;display:inline-block;padding:7px 18px;font-size:16px;font-weight:700;color:#3A2215;background-color:rgba(255,255,255,0.75);border-radius:999px">기간</p></div></div>'),
(2, '선착순 쿠폰 전단지 한상 메뉴할인',
 '<div style="display:flex;flex-direction:column;width:100%;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;padding:28px 24px;background:linear-gradient(160deg,#FFF6E9,#F4D9B8);color:#3A2215;font-family:sans-serif;text-align:center;border-radius:24px"><div style="align-self:center;width:56px;height:6px;border-radius:12px;background-color:#B23A1E;margin-bottom:14px"></div><h1 data-slot="eventName" style="margin:0 0 14px;font-size:34px;line-height:1.25;font-weight:800;word-break:keep-all;color:#3A2215">이벤트명</h1><img data-slot="image" alt="메뉴 이미지" style="display:block;flex:1;min-height:0;width:100%;object-fit:cover;border-radius:24px 24px 12px 12px;background-color:rgba(255,255,255,0.75);box-shadow:0 8px 18px rgba(0,0,0,0.18);margin-bottom:18px"><p data-slot="discount" style="margin:0 0 14px;padding:10px 4px;font-size:28px;line-height:1.3;font-weight:800;word-break:keep-all;color:#B23A1E;border-top:2px dashed #B23A1E;border-bottom:2px dashed #B23A1E">할인 내용</p><div><p data-slot="period" style="margin:0;display:inline-block;padding:7px 18px;font-size:16px;font-weight:700;color:#3A2215;background-color:rgba(255,255,255,0.75);border-radius:999px">기간</p></div></div>'),
(3, '선착순 쿠폰 전단지 홍등 전체할인',
 '<div style="display:flex;flex-direction:column;width:100%;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;padding:28px 24px;background:linear-gradient(160deg,#B71C1C,#7F0E0E);color:#FFF8E1;font-family:sans-serif;text-align:center;border-radius:24px"><div style="align-self:center;width:56px;height:6px;border-radius:12px;background-color:#FFD54F;margin-bottom:14px"></div><h1 data-slot="eventName" style="margin:0 0 14px;font-size:34px;line-height:1.25;font-weight:800;word-break:keep-all;color:#FFF8E1">이벤트명</h1><img data-slot="image" alt="메뉴 이미지" style="display:block;flex:1;min-height:0;width:100%;object-fit:cover;border-radius:16px;background-color:rgba(255,255,255,0.15);border:3px solid #FFD54F;margin-bottom:16px"><div style="background-color:#FFD54F;border-radius:16px;padding:14px 12px;margin-bottom:14px"><p data-slot="discount" style="margin:0;font-size:28px;line-height:1.3;font-weight:800;word-break:keep-all;color:#7F0E0E">할인 내용</p></div><div><p data-slot="period" style="margin:0;display:inline-block;padding:7px 18px;font-size:16px;font-weight:700;color:#FFF8E1;background-color:rgba(255,255,255,0.15);border-radius:999px">기간</p></div></div>'),
(4, '선착순 쿠폰 전단지 홍등 메뉴할인',
 '<div style="display:flex;flex-direction:column;width:100%;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;padding:28px 24px;background:linear-gradient(160deg,#B71C1C,#7F0E0E);color:#FFF8E1;font-family:sans-serif;text-align:center;border-radius:24px"><div style="align-self:center;width:56px;height:6px;border-radius:12px;background-color:#FFD54F;margin-bottom:14px"></div><h1 data-slot="eventName" style="margin:0 0 14px;font-size:34px;line-height:1.25;font-weight:800;word-break:keep-all;color:#FFF8E1">이벤트명</h1><img data-slot="image" alt="메뉴 이미지" style="display:block;flex:1;min-height:0;width:100%;object-fit:cover;border-radius:24px 24px 12px 12px;background-color:rgba(255,255,255,0.15);box-shadow:0 8px 18px rgba(0,0,0,0.18);margin-bottom:18px"><p data-slot="discount" style="margin:0 0 14px;padding:10px 4px;font-size:28px;line-height:1.3;font-weight:800;word-break:keep-all;color:#FFD54F;border-top:2px dashed #FFD54F;border-bottom:2px dashed #FFD54F">할인 내용</p><div><p data-slot="period" style="margin:0;display:inline-block;padding:7px 18px;font-size:16px;font-weight:700;color:#FFF8E1;background-color:rgba(255,255,255,0.15);border-radius:999px">기간</p></div></div>'),
(5, '선착순 쿠폰 전단지 와 전체할인',
 '<div style="display:flex;flex-direction:column;width:100%;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;padding:28px 24px;background:linear-gradient(180deg,#F7F4EE,#E6DECF);color:#1F2A44;font-family:sans-serif;text-align:center;border-radius:24px"><div style="align-self:center;width:56px;height:6px;border-radius:12px;background-color:#C8553D;margin-bottom:14px"></div><h1 data-slot="eventName" style="margin:0 0 14px;font-size:34px;line-height:1.25;font-weight:800;word-break:keep-all;color:#1F2A44">이벤트명</h1><img data-slot="image" alt="메뉴 이미지" style="display:block;flex:1;min-height:0;width:100%;object-fit:cover;border-radius:16px;background-color:rgba(255,255,255,0.75);border:3px solid #C8553D;margin-bottom:16px"><div style="background-color:#C8553D;border-radius:16px;padding:14px 12px;margin-bottom:14px"><p data-slot="discount" style="margin:0;font-size:28px;line-height:1.3;font-weight:800;word-break:keep-all;color:#FFFFFF">할인 내용</p></div><div><p data-slot="period" style="margin:0;display:inline-block;padding:7px 18px;font-size:16px;font-weight:700;color:#1F2A44;background-color:rgba(255,255,255,0.75);border-radius:999px">기간</p></div></div>'),
(6, '선착순 쿠폰 전단지 와 메뉴할인',
 '<div style="display:flex;flex-direction:column;width:100%;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;padding:28px 24px;background:linear-gradient(180deg,#F7F4EE,#E6DECF);color:#1F2A44;font-family:sans-serif;text-align:center;border-radius:24px"><div style="align-self:center;width:56px;height:6px;border-radius:12px;background-color:#C8553D;margin-bottom:14px"></div><h1 data-slot="eventName" style="margin:0 0 14px;font-size:34px;line-height:1.25;font-weight:800;word-break:keep-all;color:#1F2A44">이벤트명</h1><img data-slot="image" alt="메뉴 이미지" style="display:block;flex:1;min-height:0;width:100%;object-fit:cover;border-radius:24px 24px 12px 12px;background-color:rgba(255,255,255,0.75);box-shadow:0 8px 18px rgba(0,0,0,0.18);margin-bottom:18px"><p data-slot="discount" style="margin:0 0 14px;padding:10px 4px;font-size:28px;line-height:1.3;font-weight:800;word-break:keep-all;color:#C8553D;border-top:2px dashed #C8553D;border-bottom:2px dashed #C8553D">할인 내용</p><div><p data-slot="period" style="margin:0;display:inline-block;padding:7px 18px;font-size:16px;font-weight:700;color:#1F2A44;background-color:rgba(255,255,255,0.75);border-radius:999px">기간</p></div></div>'),
(7, '선착순 쿠폰 전단지 비스트로 전체할인',
 '<div style="display:flex;flex-direction:column;width:100%;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;padding:28px 24px;background:linear-gradient(170deg,#FBF7F0,#EAD9C2);color:#2E1F17;font-family:sans-serif;text-align:center;border-radius:24px"><div style="align-self:center;width:56px;height:6px;border-radius:12px;background-color:#6B3E26;margin-bottom:14px"></div><h1 data-slot="eventName" style="margin:0 0 14px;font-size:34px;line-height:1.25;font-weight:800;word-break:keep-all;color:#2E1F17">이벤트명</h1><img data-slot="image" alt="메뉴 이미지" style="display:block;flex:1;min-height:0;width:100%;object-fit:cover;border-radius:16px;background-color:rgba(255,255,255,0.75);border:3px solid #6B3E26;margin-bottom:16px"><div style="background-color:#6B3E26;border-radius:16px;padding:14px 12px;margin-bottom:14px"><p data-slot="discount" style="margin:0;font-size:28px;line-height:1.3;font-weight:800;word-break:keep-all;color:#FBF7F0">할인 내용</p></div><div><p data-slot="period" style="margin:0;display:inline-block;padding:7px 18px;font-size:16px;font-weight:700;color:#2E1F17;background-color:rgba(255,255,255,0.75);border-radius:999px">기간</p></div></div>'),
(8, '선착순 쿠폰 전단지 비스트로 메뉴할인',
 '<div style="display:flex;flex-direction:column;width:100%;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;padding:28px 24px;background:linear-gradient(170deg,#FBF7F0,#EAD9C2);color:#2E1F17;font-family:sans-serif;text-align:center;border-radius:24px"><div style="align-self:center;width:56px;height:6px;border-radius:12px;background-color:#6B3E26;margin-bottom:14px"></div><h1 data-slot="eventName" style="margin:0 0 14px;font-size:34px;line-height:1.25;font-weight:800;word-break:keep-all;color:#2E1F17">이벤트명</h1><img data-slot="image" alt="메뉴 이미지" style="display:block;flex:1;min-height:0;width:100%;object-fit:cover;border-radius:24px 24px 12px 12px;background-color:rgba(255,255,255,0.75);box-shadow:0 8px 18px rgba(0,0,0,0.18);margin-bottom:18px"><p data-slot="discount" style="margin:0 0 14px;padding:10px 4px;font-size:28px;line-height:1.3;font-weight:800;word-break:keep-all;color:#6B3E26;border-top:2px dashed #6B3E26;border-bottom:2px dashed #6B3E26">할인 내용</p><div><p data-slot="period" style="margin:0;display:inline-block;padding:7px 18px;font-size:16px;font-weight:700;color:#2E1F17;background-color:rgba(255,255,255,0.75);border-radius:999px">기간</p></div></div>'),
(9, '선착순 쿠폰 전단지 팝 전체할인',
 '<div style="display:flex;flex-direction:column;width:100%;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;padding:28px 24px;background:linear-gradient(160deg,#FFE14D,#FFBF2E);color:#1D1D1D;font-family:sans-serif;text-align:center;border-radius:24px"><div style="align-self:center;width:56px;height:6px;border-radius:12px;background-color:#E63946;margin-bottom:14px"></div><h1 data-slot="eventName" style="margin:0 0 14px;font-size:34px;line-height:1.25;font-weight:800;word-break:keep-all;color:#1D1D1D">이벤트명</h1><img data-slot="image" alt="메뉴 이미지" style="display:block;flex:1;min-height:0;width:100%;object-fit:cover;border-radius:16px;background-color:rgba(255,255,255,0.8);border:3px solid #E63946;margin-bottom:16px"><div style="background-color:#E63946;border-radius:16px;padding:14px 12px;margin-bottom:14px"><p data-slot="discount" style="margin:0;font-size:28px;line-height:1.3;font-weight:800;word-break:keep-all;color:#FFFFFF">할인 내용</p></div><div><p data-slot="period" style="margin:0;display:inline-block;padding:7px 18px;font-size:16px;font-weight:700;color:#1D1D1D;background-color:rgba(255,255,255,0.8);border-radius:999px">기간</p></div></div>'),
(10, '선착순 쿠폰 전단지 팝 메뉴할인',
 '<div style="display:flex;flex-direction:column;width:100%;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;padding:28px 24px;background:linear-gradient(160deg,#FFE14D,#FFBF2E);color:#1D1D1D;font-family:sans-serif;text-align:center;border-radius:24px"><div style="align-self:center;width:56px;height:6px;border-radius:12px;background-color:#E63946;margin-bottom:14px"></div><h1 data-slot="eventName" style="margin:0 0 14px;font-size:34px;line-height:1.25;font-weight:800;word-break:keep-all;color:#1D1D1D">이벤트명</h1><img data-slot="image" alt="메뉴 이미지" style="display:block;flex:1;min-height:0;width:100%;object-fit:cover;border-radius:24px 24px 12px 12px;background-color:rgba(255,255,255,0.8);box-shadow:0 8px 18px rgba(0,0,0,0.18);margin-bottom:18px"><p data-slot="discount" style="margin:0 0 14px;padding:10px 4px;font-size:28px;line-height:1.3;font-weight:800;word-break:keep-all;color:#E63946;border-top:2px dashed #E63946;border-bottom:2px dashed #E63946">할인 내용</p><div><p data-slot="period" style="margin:0;display:inline-block;padding:7px 18px;font-size:16px;font-weight:700;color:#1D1D1D;background-color:rgba(255,255,255,0.8);border-radius:999px">기간</p></div></div>'),
(11, '작성 중 전단지',
 '<div style="display:flex;flex-direction:column;width:100%;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;padding:28px 24px;background:linear-gradient(160deg,#FFF6E9,#F4D9B8);color:#3A2215;font-family:sans-serif;text-align:center;border-radius:24px"><div style="align-self:center;width:56px;height:6px;border-radius:12px;background-color:#B23A1E;margin-bottom:14px"></div><h1 data-slot="eventName" style="margin:0 0 14px;font-size:34px;line-height:1.25;font-weight:800;word-break:keep-all;color:#3A2215">이벤트명</h1><img data-slot="image" alt="메뉴 이미지" style="display:block;flex:1;min-height:0;width:100%;object-fit:cover;border-radius:16px;background-color:rgba(255,255,255,0.75);border:3px solid #B23A1E;margin-bottom:16px"><div style="background-color:#B23A1E;border-radius:16px;padding:14px 12px;margin-bottom:14px"><p data-slot="discount" style="margin:0;font-size:28px;line-height:1.3;font-weight:800;word-break:keep-all;color:#FFF6E9">할인 내용</p></div><div><p data-slot="period" style="margin:0;display:inline-block;padding:7px 18px;font-size:16px;font-weight:700;color:#3A2215;background-color:rgba(255,255,255,0.75);border-radius:999px">기간</p></div></div>'),
(12, '작성 중 전단지 버전 25',
 '<div style="display:flex;flex-direction:column;width:100%;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;padding:28px 24px;background:linear-gradient(160deg,#FFF6E9,#F4D9B8);color:#3A2215;font-family:sans-serif;text-align:center;border-radius:24px"><div style="align-self:center;width:56px;height:6px;border-radius:12px;background-color:#B23A1E;margin-bottom:14px"></div><h1 data-slot="eventName" style="margin:0 0 14px;font-size:34px;line-height:1.25;font-weight:800;word-break:keep-all;color:#3A2215">이벤트명</h1><img data-slot="image" alt="메뉴 이미지" style="display:block;flex:1;min-height:0;width:100%;object-fit:cover;border-radius:24px 24px 12px 12px;background-color:rgba(255,255,255,0.75);box-shadow:0 8px 18px rgba(0,0,0,0.18);margin-bottom:18px"><p data-slot="discount" style="margin:0 0 14px;padding:10px 4px;font-size:28px;line-height:1.3;font-weight:800;word-break:keep-all;color:#B23A1E;border-top:2px dashed #B23A1E;border-bottom:2px dashed #B23A1E">할인 내용</p><div><p data-slot="period" style="margin:0;display:inline-block;padding:7px 18px;font-size:16px;font-weight:700;color:#3A2215;background-color:rgba(255,255,255,0.75);border-radius:999px">기간</p></div></div>'),
(13, '게시 중지 전단지 A',
 '<div style="display:flex;flex-direction:column;width:100%;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;padding:28px 24px;background:linear-gradient(160deg,#FFE14D,#FFBF2E);color:#1D1D1D;font-family:sans-serif;text-align:center;border-radius:24px"><div style="align-self:center;width:56px;height:6px;border-radius:12px;background-color:#E63946;margin-bottom:14px"></div><h1 data-slot="eventName" style="margin:0 0 14px;font-size:34px;line-height:1.25;font-weight:800;word-break:keep-all;color:#1D1D1D">이벤트명</h1><img data-slot="image" alt="메뉴 이미지" style="display:block;flex:1;min-height:0;width:100%;object-fit:cover;border-radius:16px;background-color:rgba(255,255,255,0.8);border:3px solid #E63946;margin-bottom:16px"><div style="background-color:#E63946;border-radius:16px;padding:14px 12px;margin-bottom:14px"><p data-slot="discount" style="margin:0;font-size:28px;line-height:1.3;font-weight:800;word-break:keep-all;color:#FFFFFF">할인 내용</p></div><div><p data-slot="period" style="margin:0;display:inline-block;padding:7px 18px;font-size:16px;font-weight:700;color:#1D1D1D;background-color:rgba(255,255,255,0.8);border-radius:999px">기간</p></div></div>'),
(14, '게시 중지 전단지 B',
 '<div style="display:flex;flex-direction:column;width:100%;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;padding:28px 24px;background:linear-gradient(160deg,#FFE14D,#FFBF2E);color:#1D1D1D;font-family:sans-serif;text-align:center;border-radius:24px"><div style="align-self:center;width:56px;height:6px;border-radius:12px;background-color:#E63946;margin-bottom:14px"></div><h1 data-slot="eventName" style="margin:0 0 14px;font-size:34px;line-height:1.25;font-weight:800;word-break:keep-all;color:#1D1D1D">이벤트명</h1><img data-slot="image" alt="메뉴 이미지" style="display:block;flex:1;min-height:0;width:100%;object-fit:cover;border-radius:24px 24px 12px 12px;background-color:rgba(255,255,255,0.8);box-shadow:0 8px 18px rgba(0,0,0,0.18);margin-bottom:18px"><p data-slot="discount" style="margin:0 0 14px;padding:10px 4px;font-size:28px;line-height:1.3;font-weight:800;word-break:keep-all;color:#E63946;border-top:2px dashed #E63946;border-bottom:2px dashed #E63946">할인 내용</p><div><p data-slot="period" style="margin:0;display:inline-block;padding:7px 18px;font-size:16px;font-weight:700;color:#1D1D1D;background-color:rgba(255,255,255,0.8);border-radius:999px">기간</p></div></div>');

/*
 * 3. 템플릿 14개.
 * TPL.PUBLISHED_ACTIVE (1 ~ 10), TPL.DRAFT (11), TPL.DRAFT_20_VERSIONS, TPL.DRAFT_25_VERSIONS (12), TPL.PUBLISHED_INACTIVE (13, 14).
 * 이미 있으면 이름, 상태, HTML 을 다시 쓴다.
 */
INSERT INTO template (template_id, name, status, html_content, active, published_at, published_version_number,
                      activated_by, activated_at, last_modified_by, last_modified_at, created_at, updated_at)
SELECT 10000000 + h.t,
       h.name,
       CASE WHEN h.t IN (11, 12) THEN 'DRAFT' ELSE 'PUBLISHED' END,
       CASE WHEN h.t NOT IN (11, 12) THEN h.html END,
       CASE WHEN h.t <= 10 THEN 1 ELSE 0 END,
       CASE WHEN h.t <= 10 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 10 DAY), '10:00:00')
            WHEN h.t >= 13 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 8 DAY), '10:00:00') END,
       CASE WHEN h.t <= 10 THEN 2 WHEN h.t >= 13 THEN 1 END,
       CASE WHEN h.t <= 10 THEN 10000001 END,
       CASE WHEN h.t <= 10 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 10 DAY), '10:05:00') END,
       10000001,
       CASE WHEN h.t <= 10 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 12 DAY), '12:00:00')
            WHEN h.t >= 13 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 9 DAY), '10:00:00')
            WHEN h.t = 11 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 1 DAY), '15:00:00')
            ELSE TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 5 DAY), '09:00:00') + INTERVAL 50 HOUR END,
       CASE WHEN h.t <= 10 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 12 DAY), '10:00:00')
            WHEN h.t >= 13 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 9 DAY), '10:00:00')
            WHEN h.t = 11 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 3 DAY), '15:00:00')
            ELSE TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 5 DAY), '09:00:00') END,
       CASE WHEN h.t <= 10 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 10 DAY), '10:05:00')
            WHEN h.t >= 13 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 8 DAY), '10:00:00')
            WHEN h.t = 11 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 1 DAY), '15:00:00')
            ELSE TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 5 DAY), '09:00:00') + INTERVAL 50 HOUR END
FROM tpl_html h
ON DUPLICATE KEY UPDATE name = VALUES(name), status = VALUES(status), html_content = VALUES(html_content),
                        active = VALUES(active), published_at = VALUES(published_at),
                        published_version_number = VALUES(published_version_number), activated_by = VALUES(activated_by),
                        activated_at = VALUES(activated_at), last_modified_by = VALUES(last_modified_by),
                        last_modified_at = VALUES(last_modified_at), updated_at = VALUES(updated_at);

/*
 * 4. 템플릿 버전.
 * 1 ~ 10 은 버전 2개(게시 때 soft delete), 13, 14 는 1개(게시 때 soft delete), 11 은 3개(D-3 ~ D-1), 12 는 25개(1 ~ 5 soft delete).
 * template_version_id = B + t * 100 + v 이다.
 */
INSERT INTO template_version (template_version_id, template_id, version_number, request_prompt, request_id,
                              html_content, message, created_at, updated_at, deleted_at)
SELECT 10000000 + v.t * 100 + v.v, 10000000 + v.t, v.v, '따뜻한 색감의 점심 할인 포스터', CONCAT('world-tpl-', v.t, '-', v.v),
       h.html, '요청하신 분위기로 템플릿을 만들었어요.', v.created_at, COALESCE(v.deleted_at, v.created_at), v.deleted_at
FROM (
    SELECT t.t, x.v,
           TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 12 DAY), '10:00:00') + INTERVAL x.v HOUR AS created_at,
           TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 10 DAY), '10:00:00') AS deleted_at
    FROM (SELECT 1 t UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6
          UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10) t
    JOIN (SELECT 1 v UNION ALL SELECT 2) x
    UNION ALL
    SELECT t.t, 1, TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 9 DAY), '10:00:00'),
           TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 8 DAY), '10:00:00')
    FROM (SELECT 13 t UNION ALL SELECT 14) t
    UNION ALL
    SELECT 11, x.v, TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 4 - x.v DAY), '15:00:00'), NULL
    FROM (SELECT 1 v UNION ALL SELECT 2 UNION ALL SELECT 3) x
    UNION ALL
    SELECT 12, x.v, TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 5 DAY), '09:00:00') + INTERVAL x.v * 2 HOUR,
           CASE WHEN x.v <= 5 THEN TIMESTAMP(DATE_SUB(DATE('${baseDate}'), INTERVAL 2 DAY), '04:00:00') END
    FROM (SELECT a.d * 5 + b.d + 1 v
          FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) a
          CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) b) x
) v
JOIN tpl_html h ON h.t = v.t;

-- template_quota 를 템플릿 행 수로 맞춘다
UPDATE template_quota
SET current_count = (SELECT COUNT(*) FROM template),
    updated_at = TIMESTAMP(DATE('${baseDate}'), '00:00:00');

/*
 * 5. 기본 세계 캠페인의 포스터 (PST.PASSED, PST.SAVED, PST.SAVED_FAILING).
 * DRAFT 222 ~ 241 은 단계 (n - 222) % 4 이고 단계 0 ~ 2 는 포스터가 없다. 그 밖의 캠페인은 모두 포스터가 있다.
 * 템플릿은 가게 업종의 스타일과 캠페인의 할인 대상(ALL, MENU)으로 고른다.
 * 한식, 분식 -> 한상(1, 2), 중식, 아시안 -> 홍등(3, 4), 일식 -> 와(5, 6), 양식, 카페디저트 -> 비스트로(7, 8), 패스트푸드, 기타 -> 팝(9, 10).
 * 전체할인이 홀수, 메뉴할인이 짝수다.
 * image 칸은 할인 대상 메뉴의 사진이고 전체 메뉴 할인이면 첫 메뉴 사진이다. 피드 가게는 모두 메뉴가 있어 로고가 들어가지 않는다 (#941). 완성 포스터 사진은 world/campaigns/<캠페인>/poster.jpg 다.
 * DRAFT 단계 3 중 241 은 검수 전, 나머지는 금지 표현 "최저가 보장" 이 들어가 검수 FAIL 이다.
 */
CREATE TEMPORARY TABLE tpl_poster AS
SELECT c.campaign_id AS id,
       c.campaign_id - 10000000 AS n,
       CASE WHEN c.status = 'DRAFT' THEN (c.campaign_id - 10000000 - 222) % 4 END AS stage,
       tp.template_id,
       tp.name AS template_name,
       tp.html_content AS template_html,
       st.name AS store_name,
       CONCAT(IF(c.discount_target_type = 'ALL', '전체 메뉴', m.name), ' ',
              IF(c.discount_type = 'PERCENT', CONCAT(c.discount_value, '%'), CONCAT(FORMAT(c.discount_value, 0), '원')), ' 할인') AS discount_text,
       CONCAT('오늘 ', TIME_FORMAT(c.usable_start_time, '%H:%i'), ' ~ ', TIME_FORMAT(c.usable_end_time, '%H:%i')) AS period_text,
       CONCAT(TRIM(TRAILING '/' FROM '${baseCdnBaseUrl}'), '/',
              COALESCE(ti.object_key, fi.object_key, CONCAT('world/stores/', st.store_id, '/logo.jpg'))) AS image_url,
       c.created_at + INTERVAL 30 MINUTE AS created_at
FROM campaign c
JOIN store st ON st.store_id = c.store_id
LEFT JOIN store_menu m ON m.store_menu_id = c.target_menu_id
LEFT JOIN store_menu_image ti ON ti.store_menu_id = c.target_menu_id
LEFT JOIN store_menu fm ON fm.store_id = st.store_id AND fm.sort_order = 1
LEFT JOIN store_menu_image fi ON fi.store_menu_id = fm.store_menu_id
JOIN template tp ON tp.template_id = 10000000 + CASE st.category_code WHEN 'KOREAN' THEN 0 WHEN 'BUNSIK' THEN 0 WHEN 'CHINESE' THEN 2 WHEN 'ASIAN' THEN 2
                            WHEN 'JAPANESE' THEN 4 WHEN 'WESTERN' THEN 6 WHEN 'CAFE_DESSERT' THEN 6 ELSE 8 END
                 + IF(c.discount_target_type = 'MENU', 2, 1)
WHERE c.campaign_id BETWEEN 10000001 AND 19999999
  AND (c.status <> 'DRAFT' OR (c.campaign_id - 10000000 - 222) % 4 = 3);

UPDATE tpl_poster SET discount_text = CONCAT(discount_text, ' 최저가 보장') WHERE stage = 3 AND n <> 241;

INSERT INTO poster (poster_id, campaign_id, template_id, title, slot_values, html_content, created_at, updated_at,
                    image_object_key)
SELECT p.id, p.id, p.template_id, LEFT(CONCAT(p.store_name, ' - ', p.template_name), 100),
       JSON_OBJECT('eventName', JSON_OBJECT('type', 'text', 'value', p.store_name),
                   'discount', JSON_OBJECT('type', 'text', 'value', p.discount_text),
                   'period', JSON_OBJECT('type', 'text', 'value', p.period_text),
                   'image', JSON_OBJECT('type', 'image', 'value', p.image_url)),
       REPLACE(REPLACE(REPLACE(REPLACE(p.template_html,
           '>이벤트명<', CONCAT('>', p.store_name, '<')),
           '>할인 내용<', CONCAT('>', p.discount_text, '<')),
           '>기간<', CONCAT('>', p.period_text, '<')),
           'alt="메뉴 이미지"', CONCAT('alt="메뉴 이미지" src="', p.image_url, '"')),
       p.created_at, p.created_at, CONCAT('world/campaigns/', p.id, '/poster.jpg')
FROM tpl_poster p;

INSERT INTO poster_moderation_result (poster_moderation_result_id, poster_id, result, reason, created_at, updated_at)
SELECT p.id, p.id, IF(p.stage = 3, 'FAIL', 'PASS'), IF(p.stage = 3, 'FORBIDDEN_EXPRESSION', NULL),
       p.created_at + INTERVAL 10 MINUTE, p.created_at + INTERVAL 10 MINUTE
FROM tpl_poster p
WHERE p.stage IS NULL OR (p.stage = 3 AND p.n <> 241);

DROP TEMPORARY TABLE tpl_poster;

/*
 * 6. 시연 캠페인의 포스터 (시연 층 R__demo 의 캠페인 7개).
 * 시연 층만 다시 돌 때를 위해 R__demo 도 같은 방식으로 포스터를 만든다. 템플릿은 기본 세계와 같은 규칙으로 고른다.
 * 시연 캠페인은 모두 전체 메뉴 할인이라 image 칸은 첫 메뉴 사진이다.
 */
INSERT INTO poster (poster_id, campaign_id, template_id, title, slot_values, html_content, created_at, updated_at,
                    image_object_key)
SELECT c.campaign_id, c.campaign_id, tp.template_id, LEFT(CONCAT(st.name, ' - ', tp.name), 100),
       JSON_OBJECT('eventName', JSON_OBJECT('type', 'text', 'value', st.name),
                   'discount', JSON_OBJECT('type', 'text', 'value', CONCAT('전체 메뉴 ', FORMAT(c.discount_value, 0), '원 할인')),
                   'period', JSON_OBJECT('type', 'text', 'value',
                       CONCAT('오늘 ', TIME_FORMAT(c.usable_start_time, '%H:%i'), ' ~ ', TIME_FORMAT(c.usable_end_time, '%H:%i'))),
                   'image', JSON_OBJECT('type', 'image', 'value', CONCAT(TRIM(TRAILING '/' FROM '${baseCdnBaseUrl}'), '/', fi.object_key))),
       REPLACE(REPLACE(REPLACE(REPLACE(tp.html_content,
           '>이벤트명<', CONCAT('>', st.name, '<')),
           '>할인 내용<', CONCAT('>전체 메뉴 ', FORMAT(c.discount_value, 0), '원 할인<')),
           '>기간<', CONCAT('>오늘 ', TIME_FORMAT(c.usable_start_time, '%H:%i'), ' ~ ', TIME_FORMAT(c.usable_end_time, '%H:%i'), '<')),
           'alt="메뉴 이미지"', CONCAT('alt="메뉴 이미지" src="', TRIM(TRAILING '/' FROM '${baseCdnBaseUrl}'), '/', fi.object_key, '"')),
       c.created_at + INTERVAL 30 MINUTE, c.created_at + INTERVAL 30 MINUTE,
       CONCAT('demo/campaigns/', c.campaign_id, '/poster.jpg')
FROM campaign c
JOIN store st ON st.store_id = c.store_id
JOIN store_menu fm ON fm.store_id = st.store_id AND fm.sort_order = 1
JOIN store_menu_image fi ON fi.store_menu_id = fm.store_menu_id
JOIN template tp ON tp.template_id = 10000000 + CASE st.category_code WHEN 'KOREAN' THEN 0 WHEN 'BUNSIK' THEN 0 WHEN 'CHINESE' THEN 2 WHEN 'ASIAN' THEN 2
                            WHEN 'JAPANESE' THEN 4 WHEN 'WESTERN' THEN 6 WHEN 'CAFE_DESSERT' THEN 6 ELSE 8 END
                 + IF(c.discount_target_type = 'MENU', 2, 1)
WHERE c.campaign_id IN (20000101, 20000111, 20000112, 20000113, 20000301, 20000401, 20000501);

INSERT INTO poster_moderation_result (poster_moderation_result_id, poster_id, result, reason, created_at, updated_at)
SELECT p.poster_id, p.poster_id, 'PASS', NULL, p.created_at + INTERVAL 10 MINUTE, p.created_at + INTERVAL 10 MINUTE
FROM poster p
WHERE p.campaign_id IN (20000101, 20000111, 20000112, 20000113, 20000301, 20000401, 20000501);

DROP TEMPORARY TABLE tpl_html;
