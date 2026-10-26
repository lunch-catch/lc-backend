import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

/*
 * 부하 시험이 회원과 관리자의 Access Token 을 직접 서명한다(#997).
 * 서버(JwtTokenProvider)는 JWT_SECRET 문자열의 바이트를 그대로 HMAC 키로 쓰고 sub 와 role 만 읽는다.
 * 키가 32바이트 이상이면 HS256 서명도 받는다.
 */
const HEADER = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');

export function accessToken(secret, id, role, validitySeconds = 3600) {
  const now = Math.floor(Date.now() / 1000);
  const payload = encoding.b64encode(
    JSON.stringify({ sub: String(id), role, iat: now, exp: now + validitySeconds }),
    'rawurl',
  );
  const signature = crypto.hmac('sha256', secret, `${HEADER}.${payload}`, 'base64rawurl');
  return `${HEADER}.${payload}.${signature}`;
}

// 서버가 경로로 쿠키 이름을 고른다(AuthCookieNames). 관리자는 /v1/admin 아래에서 adminAccessToken 을 읽는다.
export function memberCookie(secret, memberId) {
  return `accessToken=${accessToken(secret, memberId, 'MEMBER')}`;
}

export function adminCookie(secret, adminId) {
  return `adminAccessToken=${accessToken(secret, adminId, 'SUPER_ADMIN')}`;
}
