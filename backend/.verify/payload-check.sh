#!/usr/bin/env bash
# AC-24 ② / AC-02「载荷无 deployVersion 键」的后端侧读数
# 用一次性主机记录（不做 SSH 测试）走真实 createInstance 写路径，检查落库 configInfo。
set -uo pipefail
BASE=http://localhost:18080
T=$(curl -s -X POST "${BASE}/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
AUTH=(-H "Authorization: Bearer ${T}" -H "Content-Type: application/json")

HOST_ID=$(curl -s -X POST "${BASE}/api/hosts" "${AUTH[@]}" -d '{
  "name":"merc23-verify-host-$(date +%s)","ip":"192.0.2.1","sshPort":22,
  "sshUsername":"nobody","sshPassword":"nobody"
}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")
echo "hostId=${HOST_ID}"

INST=$(curl -s -X POST "${BASE}/api/instances" "${AUTH[@]}" -d "{
  \"instanceName\":\"merc23-dnf-tw-01\",\"gameId\":\"41\",\"hostId\":\"${HOST_ID}\",
  \"deployType\":\"docker-compose\",\"installPath\":\"/tmp/merc23\",
  \"portConfig\":{\"game\":17001,\"mysql\":13000,\"gate\":15505},
  \"configInfo\":{\"DNF_DB_ROOT_PASSWORD\":\"x\",\"GATE_AES_KEY\":\"y\"}
}")
IID=$(echo "${INST}" | python -c "import sys,json;d=json.load(sys.stdin);print(d.get('data') or '')")
echo "createInstance => $(echo "${INST}" | python -c "import sys,json;print(json.load(sys.stdin).get('code'))") instanceId=${IID}"

echo "=== 落库 configInfo 键集合（AC-24 ② 判 deployVersion 是否存在） ==="
curl -s "${BASE}/api/instances/${IID}" "${AUTH[@]}" | python -c "
import sys, json
d = json.load(sys.stdin).get('data') or {}
cfg = d.get('configInfo') or {}
print('  instance.runStatusDesc =', d.get('runStatusDesc'))
print('  configInfo keys        =', sorted(cfg.keys()))
print('  deployVersion present  =', 'deployVersion' in cfg, '  (期望 False)')
print('  deployVersion value    =', repr(cfg.get('deployVersion')))
"
echo "=== 清理一次性主机与实例（仅本验证用的临时库） ==="
curl -s -X DELETE "${BASE}/api/instances/${IID}" "${AUTH[@]}" >/dev/null
curl -s -X DELETE "${BASE}/api/hosts/${HOST_ID}" "${AUTH[@]}" >/dev/null
echo done
