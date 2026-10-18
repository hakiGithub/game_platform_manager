#!/usr/bin/env bash
# MERC-23 阻塞解除回补：在线读数（AC-23 ①② / §16.4 GET 三字段 / AC-24 ①②③ / AC-23 ③）
set -uo pipefail
PORT=18080
BASE="http://localhost:${PORT}"
TOKEN=$(curl -s -X POST "${BASE}/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
AUTH=(-H "Authorization: Bearer ${TOKEN}")

echo "===================== AC-23 ① 已加载插件清单 ====================="
curl -s "${BASE}/api/pf4j/plugins" "${AUTH[@]}" | python -c "
import sys, json
d = json.load(sys.stdin)['data']
for p in d:
    print('  pluginId=%-18s state=%-10s gameCode=%-8s version=%s' % (
        p.get('pluginId'), p.get('state'), p.get('gameCode'), p.get('version')))
print('  插件总数 =', len(d))
"

echo "===================== AC-23 ① getGameCode() 实际读数 ====================="
curl -s "${BASE}/api/pf4j/plugins/plugin-dnf-tw" "${AUTH[@]}" | head -c 600
echo

echo "===================== AC-23 ③ core 主源码含 \"dnf_tw\" 字面量 ====================="
HITS=$(grep -rn '"dnf_tw"' backend/core/src/main/java --include=*.java | wc -l | tr -d ' ')
echo "  grep -rn '\"dnf_tw\"' backend/core/src/main/java --include=*.java | 命中数 = ${HITS}  (期望 0)"

echo "===================== 游戏目录里的 dnf-tw gameId ====================="
GAME=$(curl -s "${BASE}/api/games/list" "${AUTH[@]}" | python -c "
import sys, json
for g in json.load(sys.stdin)['data']:
    if g.get('gameCode') == 'dnf_tw':
        print(g['id']); break
")
echo "  dnf_tw gameId = ${GAME}"

for DT in docker-compose linuxgsm docker linuxgsm-docker; do
  echo "===================== §16.4 / AC-24 ①②③ deployType=${DT} ====================="
  curl -s "${BASE}/api/games/${GAME}/deploy-config/${DT}" "${AUTH[@]}" | python -c "
import sys, json
r = json.load(sys.stdin)
d = r.get('data') or {}
print('  HTTP code          =', r.get('code'))
print('  deployVersions     =', json.dumps(d.get('deployVersions')))
print('  versionCatalogState=', repr(d.get('versionCatalogState')))
print('  versionCatalogReason present =', 'versionCatalogReason' in d, '=>', repr(d.get('versionCatalogReason')))
print('  keys total         =', len(d))
"
done
