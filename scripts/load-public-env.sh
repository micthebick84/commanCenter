#!/usr/bin/env bash
#
# 운영 설정/비밀 파일 로더 — start-public.sh / start-all.sh 가 source 한다 (단독 실행용 아님).
#
#   ~/netis-maker/public.env      (경로는 PUBLIC_ENV_FILE 로 override)
#
# KEY=VALUE 한 줄씩, '#' 주석, 값 양끝의 따옴표 한 쌍은 벗긴다. 레포 밖에 두어 비밀이 커밋되지 않게 한다.
# 이미 환경에 있는 변수가 파일보다 우선(환경변수 > 파일 > 스크립트 기본값) — Windows start-public.ps1 이 읽는
# %USERPROFILE%\netis-maker\public.env 와 같은 규칙. 파일이 없으면 조용히 건너뛴다.
# 키 예: NETISMAKER_DB_SECRET_KEY / NETISMAKER_DB_SECRET_SALT (질문 세션 DB 접속정보 암호화 — API, 없으면 기능만 꺼짐),
#        WORKER_API_KEY, GITHUB_PAT, GITLAB_TOKEN, AUTH_ORIGIN, APP_ORIGIN.
PUBLIC_ENV_FILE="${PUBLIC_ENV_FILE:-$HOME/netis-maker/public.env}"
if [ -f "$PUBLIC_ENV_FILE" ]; then
  _pe_re='^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*=[[:space:]]*(.*)$'
  while IFS= read -r _pe_line || [ -n "$_pe_line" ]; do
    [[ $_pe_line =~ $_pe_re ]] || continue
    _pe_k="${BASH_REMATCH[1]}"; _pe_v="${BASH_REMATCH[2]}"
    _pe_v="${_pe_v%"${_pe_v##*[![:space:]]}"}"   # 뒤쪽 공백 제거
    case "$_pe_v" in
      \"*\") _pe_v="${_pe_v#\"}"; _pe_v="${_pe_v%\"}" ;;
      \'*\') _pe_v="${_pe_v#\'}"; _pe_v="${_pe_v%\'}" ;;
    esac
    if [ -z "${!_pe_k+x}" ]; then export "$_pe_k=$_pe_v"; fi
  done < "$PUBLIC_ENV_FILE"
  unset _pe_re _pe_line _pe_k _pe_v
  echo "● 설정 파일 로드: $PUBLIC_ENV_FILE"
fi
