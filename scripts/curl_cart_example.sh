#!/usr/bin/env bash
# docs/06 引用的购物车端到端验收脚本。
# 创建一个带时间戳的本地测试用户，全程不显示 access token，
# 并验证使用旧版本号 PATCH 购物车行会返回 HTTP 409。
# 优先使用 jq；没有 jq 时退回 python3/python 提取 JSON 字段。
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
GOODS_ID="${GOODS_ID:-12}"
STAMP="$(date +%s)"
USERNAME="cart-user-$STAMP"
EMAIL="cart-user-$STAMP@example.test"
PASSWORD="correct-horse-battery-staple"
CURL=(curl --noproxy '*' -sS)

fail() { echo "FAIL: $*" >&2; exit 1; }

if command -v jq >/dev/null; then
  jget_field() { jq -r "$1"; }               # jget_field '.id'
  jget_qty() { jq -r --argjson id "$1" '.items[] | select(.id == $id) | .quantity'; }
else
  PYC=$(command -v python3 || command -v python) || fail "需要 jq 或 python"
  jget_field() { "$PYC" -c "
import sys, json
d = json.load(sys.stdin)
for p in '''$1'''.lstrip('.').split('.'):
    d = d[int(p)] if isinstance(d, list) else d[p]
print('null' if d is None else (json.dumps(d) if isinstance(d, (dict, list)) else d))"; }
  jget_qty() { "$PYC" -c "
import sys, json
d = json.load(sys.stdin)
for it in d['items']:
    if it['id'] == $1:
        print(it['quantity'])
        break"; }
fi

echo "1) 注册用户 $USERNAME"
REGISTER=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"agreement_accepted\":true}") \
  || fail "注册失败"
TOKEN=$(printf '%s' "$REGISTER" | jget_field '.access_token')
[ -n "$TOKEN" ] && [ "$TOKEN" != null ] || fail "注册响应缺少 access_token"

AUTH=(-H "Authorization: Bearer $TOKEN")

echo "2) 加入购物车（goods_id=$GOODS_ID, quantity=2）"
ADD=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/me/cart" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -d "{\"goods_id\":$GOODS_ID,\"quantity\":2}") \
  || fail "加入购物车失败"
ITEM_ID=$(printf '%s' "$ADD" | jget_field '.id')
VERSION=$(printf '%s' "$ADD" | jget_field '.version')
[ "$ITEM_ID" != null ] || fail "购物车响应缺少 id"
[ "$VERSION" != null ] || fail "购物车响应缺少 version"

echo "3) 用当前版本 PATCH quantity=3（应 2xx）"
CODE=$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X PATCH "$BASE_URL/api/v1/me/cart/$ITEM_ID" \
  "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "{\"quantity\":3,\"version\":$VERSION}")
[ "${CODE:0:1}" = "2" ] || fail "合法 PATCH 期望 2xx，实际 $CODE"

echo "4) 用旧版本 $VERSION 再次 PATCH（应 409）"
CODE=$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X PATCH "$BASE_URL/api/v1/me/cart/$ITEM_ID" \
  "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "{\"quantity\":4,\"version\":$VERSION}")
[ "$CODE" = "409" ] || fail "旧版本 PATCH 期望 409，实际 $CODE"

echo "5) 校验列表数量已更新为 3"
QTY=$("${CURL[@]}" "$BASE_URL/api/v1/me/cart" "${AUTH[@]}" | jget_qty "$ITEM_ID")
[ "$QTY" = "3" ] || fail "期望 quantity=3，实际 $QTY"

echo "6) 删除购物车行（应 2xx）"
CODE=$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X DELETE \
  "$BASE_URL/api/v1/me/cart/$ITEM_ID" "${AUTH[@]}")
[ "${CODE:0:1}" = "2" ] || fail "删除购物车行期望 2xx，实际 $CODE"

echo "PASS: 购物车乐观锁验收通过（token 未打印）"
