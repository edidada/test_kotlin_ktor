#!/usr/bin/env bash
# 全量本地验收：按 docs/06 的顺序跑通账号、红包、邮箱、余额、地址、订单、回调、
# 收藏、标签、缺货登记、评论、留言、订阅与改密流程。不依赖 jq（用 python 提取字段）。
set -uo pipefail
BASE_URL="${BASE_URL:-http://127.0.0.1:18080}"
DB="${DB:-data/ecshop.sqlite3}"
SECRET="${PAYMENT_CALLBACK_SECRET:-dev-payment-callback-secret}"
PYC=$(command -v python3 || command -v python)
SQLITE=$(command -v sqlite3 || echo "/c/Users/wdidada/AppData/Local/Android/Sdk/platform-tools/sqlite3")
CURL=(curl --noproxy '*' -sS)
STAMP=$(date +%s)
USER="acc-user-$STAMP"
MAIL="acc-user-$STAMP@example.test"
PASS1="correct-horse-battery-staple"
FAILED=0

jget() { "$PYC" -c "
import sys, json
try: d = json.load(sys.stdin)
except Exception: print('PARSE_ERR'); sys.exit()
for p in '''$1'''.lstrip('.').split('.'):
    if isinstance(d, list): d = d[int(p)]
    if d is None: print('null'); sys.exit()
    d = d[p]
print('null' if d is None else (json.dumps(d) if isinstance(d,(dict,list)) else (str(d).lower() if isinstance(d,bool) else d)))"; }

check() { # check <desc> <expected-prefix> <actual>
  if [[ "$3" == "$2"* ]]; then echo "ok   $1"; else echo "FAIL $1 (expect $2*, got $3)"; FAILED=$((FAILED+1)); fi
}
check2() { # check2 <desc> <cond-cmd args..>: 前缀 2xx 判断
  if [[ "$3" == ${2}* ]]; then echo "ok   $1"; else echo "FAIL $1 (want prefix $2, got $3)"; FAILED=$((FAILED+1)); fi
}

echo "=== 认证与可用性 ==="
check "availability/username" "true" "$("${CURL[@]}" -X POST "$BASE_URL/api/v1/auth/availability/username" -H 'Content-Type: application/json' -d "{\"username\":\"$USER\"}" | jget .available)"
check "availability/email" "true" "$("${CURL[@]}" -X POST "$BASE_URL/api/v1/auth/availability/email" -H 'Content-Type: application/json' -d "{\"email\":\"$MAIL\"}" | jget .available)"
REG=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"email\":\"$MAIL\",\"password\":\"$PASS1\",\"agreement_accepted\":true}")
TOKEN=$(printf '%s' "$REG" | jget .access_token)
[ "$TOKEN" != null ] || { echo "FAIL register 无 access_token: $REG"; exit 1; }
echo "ok   register"
AUTH=(-H "Authorization: Bearer $TOKEN")
check "重复注册 409" "409" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/auth/register" -H 'Content-Type: application/json' -d "{\"username\":\"$USER\",\"email\":\"$MAIL\",\"password\":\"$PASS1\",\"agreement_accepted\":true}")"

echo "=== 红包 ==="
"$SQLITE" "$DB" "UPDATE ecs_user_bonus SET user_id=0, order_id=0 WHERE bonus_sn=202609230001"  # 可重复验收：重置示例红包

check "领取红包 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/me/bonuses/claim" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"bonus_sn":"202609230001"}')"
check "重复领取 409" "409" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/me/bonuses/claim" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"bonus_sn":"202609230001"}')"
check "红包列表" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/me/bonuses" "${AUTH[@]}")"

echo "=== 邮箱验证 ==="
check "发送验证邮件 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/me/email-verifications" "${AUTH[@]}")"
VTOKEN=$("$SQLITE" "$DB" "SELECT json_extract(payload,'\$.token') FROM ecs_email_outbox WHERE template_name='verify_email' ORDER BY message_id DESC LIMIT 1")
check "验证邮箱 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/email-verifications/confirm" -H 'Content-Type: application/json' -d "{\"token\":\"$VTOKEN\"}")"
check "重复验证 409" "409" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/email-verifications/confirm" -H 'Content-Type: application/json' -d "{\"token\":\"$VTOKEN\"}")"

echo "=== 余额申请 ==="
DEP=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/me/account/requests" "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"kind":"deposit","amount":"100.00","payment_id":1,"note":"acc deposit"}')
DEP_ID=$(printf '%s' "$DEP" | jget .id)
[ "$DEP_ID" != null ] && echo "ok   deposit 申请 id=$DEP_ID" || { echo "FAIL deposit: $DEP"; FAILED=$((FAILED+1)); }
check "申请列表" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/me/account/requests" "${AUTH[@]}")"
check "流水列表" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/me/account/transactions" "${AUTH[@]}")"
check "充值支付 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/me/account/requests/$DEP_ID/payment" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"payment_id":1}')"
CODE=$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X DELETE "$BASE_URL/api/v1/me/account/requests/$DEP_ID" "${AUTH[@]}")
# payment 只登记支付意图（入账由回调完成），请求仍是 pending_payment，可取消。
check "未入账充值申请可取消 2xx" "2" "$CODE"

echo "=== 地址与结算 ==="
ADDR=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/me/addresses" "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"consignee":"测试用户","country_id":1,"province_id":2,"city_id":52,"district_id":500,"address":"中山路 1 号","mobile":"13800000000","zipcode":"200000","is_default":true}')
ADDR_ID=$(printf '%s' "$ADDR" | jget .id)
[ "$ADDR_ID" != null ] && echo "ok   地址 id=$ADDR_ID" || { echo "FAIL address: $ADDR"; FAILED=$((FAILED+1)); }
check "checkout/options" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/checkout/options" "${AUTH[@]}")"
QUOTE=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/checkout/quote" "${AUTH[@]}" -H 'Content-Type: application/json' -d "{\"address_id\":$ADDR_ID,\"shipping_id\":1,\"payment_id\":1}")
check "quote 空车 409(未加购)" "true" "$(printf '%s' "$QUOTE" | grep -o '"code":"[a-z_]*"' | grep -q invalid_state && echo true || echo "$QUOTE")"

echo "=== 购物车 -> 下单 -> 幂等 ==="
"${CURL[@]}" -o /dev/null -X POST "$BASE_URL/api/v1/me/cart" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"goods_id":12,"quantity":2}'
QUOTE=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/checkout/quote" "${AUTH[@]}" -H 'Content-Type: application/json' -d "{\"address_id\":$ADDR_ID,\"shipping_id\":1,\"payment_id\":1}")
check "quote 有车 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/checkout/quote" "${AUTH[@]}" -H 'Content-Type: application/json' -d "{\"address_id\":$ADDR_ID,\"shipping_id\":1,\"payment_id\":1}")"
ORDER=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/orders" "${AUTH[@]}" -H 'Content-Type: application/json' -H 'Idempotency-Key: acc-order-001' \
  -d "{\"address_id\":$ADDR_ID,\"shipping_id\":1,\"payment_id\":1,\"remark\":\"acc test\"}")
ORDER_ID=$(printf '%s' "$ORDER" | jget .id)
ORDER_SN=$(printf '%s' "$ORDER" | jget .order_sn)
ORDER_AMT=$(printf '%s' "$ORDER" | jget .order_amount)
[ "$ORDER_ID" != null ] && echo "ok   下单 id=$ORDER_ID sn=$ORDER_SN amount=$ORDER_AMT" || { echo "FAIL order: $ORDER"; FAILED=$((FAILED+1)); }
REPLAY=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/orders" "${AUTH[@]}" -H 'Content-Type: application/json' -H 'Idempotency-Key: acc-order-001' \
  -d "{\"address_id\":$ADDR_ID,\"shipping_id\":1,\"payment_id\":1,\"remark\":\"acc test\"}")
check "幂等重放 replayed=true" "true" "$(printf '%s' "$REPLAY" | jget .replayed)"
check "同键不同 body 409" "409" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/orders" "${AUTH[@]}" -H 'Content-Type: application/json' -H 'Idempotency-Key: acc-order-001' -d "{\"address_id\":$ADDR_ID,\"shipping_id\":1,\"payment_id\":1,\"remark\":\"different\"}")"
check "下单后购物车为空" "0" "$("${CURL[@]}" "$BASE_URL/api/v1/me/cart" "${AUTH[@]}" | jget .total_quantity)"
check "订单列表" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/me/orders" "${AUTH[@]}")"
check "订单详情" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/me/orders/$ORDER_ID" "${AUTH[@]}")"
check "订单号查状态" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/me/orders/by-number/$ORDER_SN/status" "${AUTH[@]}")"

echo "=== 余额抵扣 / 改支付方式 ==="
"$SQLITE" "$DB" "INSERT INTO ecs_account_balance(user_id,available_cents,frozen_cents) SELECT user_id,2000,0 FROM ecs_users WHERE user_name='$USER' ON CONFLICT(user_id) DO UPDATE SET available_cents=2000,frozen_cents=0"
check "余额抵扣 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X PATCH "$BASE_URL/api/v1/me/orders/$ORDER_ID/surplus" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"amount":"10.00"}')"
check "改用线下转账 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X PATCH "$BASE_URL/api/v1/me/orders/$ORDER_ID/payment" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"payment_id":2}')"
check "改地址 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X PATCH "$BASE_URL/api/v1/me/orders/$ORDER_ID/address" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"consignee":"Alice Updated","email":"alice@example.com","address":"Shanghai updated address","zipcode":"200000","tel":"021-12345678","mobile":"13900000000","sign_building":"Building A","best_time":"18:00-20:00"}')"

echo "=== 支付回调 ==="
NEW_AMT=$("${CURL[@]}" "$BASE_URL/api/v1/me/orders/$ORDER_ID" "${AUTH[@]}" | jget .order.order_amount)
CB_BODY="{\"provider_trade_no\":\"acc-trade-$STAMP\",\"order_sn\":\"$ORDER_SN\",\"amount\":\"$NEW_AMT\"}"
SIGN=$(printf '%s' "$CB_BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.* //')
CB=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/payments/mockpay/callback" -H 'Content-Type: application/json' -H "X-Payment-Signature: $SIGN" -d "$CB_BODY")
check "回调成功" "true" "$(printf '%s' "$CB" | grep -qE '"status"' && echo true || echo "$CB")"
check "回调重放 replayed=true" "true" "$("${CURL[@]}" -X POST "$BASE_URL/api/v1/payments/mockpay/callback" -H 'Content-Type: application/json' -H "X-Payment-Signature: $SIGN" -d "$CB_BODY" | jget .replayed)"
check "错误签名 401" "401" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/payments/mockpay/callback" -H 'Content-Type: application/json' -H 'X-Payment-Signature: 0000000000000000000000000000000000000000000000000000000000000000' -d "$CB_BODY")"
check "已支付订单确认收货 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/me/orders/$ORDER_ID/received" "${AUTH[@]}")"
check "重复确认收货 409" "409" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/me/orders/$ORDER_ID/received" "${AUTH[@]}")"
check "再次取消已收货订单 409" "409" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/me/orders/$ORDER_ID/cancel" "${AUTH[@]}")"
check "return_to_cart 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/me/orders/$ORDER_ID/cart" "${AUTH[@]}")"

echo "=== 收藏 / 标签 / 缺货登记 ==="
FAV=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/me/favorites" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"goods_id":12}')
FAV_ID=$(printf '%s' "$FAV" | jget .id)
[ "$FAV_ID" != null ] && echo "ok   收藏 id=$FAV_ID" || { echo "FAIL favorite: $FAV"; FAILED=$((FAILED+1)); }
check "重复收藏 409" "409" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/me/favorites" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"goods_id":12}')"
check "收藏 attention 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X PATCH "$BASE_URL/api/v1/me/favorites/$FAV_ID" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"attention":true}')"
check "删除收藏 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X DELETE "$BASE_URL/api/v1/me/favorites/$FAV_ID" "${AUTH[@]}")"
TAGS=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/goods/12/tags" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"tag":"acc-tag-a,acc-tag-b,acc-tag-a"}')
check "打标签 2xx" "true" "$(printf '%s' "$TAGS" | grep -q acc-tag-a && echo true || echo "$TAGS")"
check "我的标签" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/me/tags" "${AUTH[@]}")"
check "删标签 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X DELETE "$BASE_URL/api/v1/me/tags" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"tag":"acc-tag-a"}')"
BOOK=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/me/bookings" "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"goods_id":14,"goods_number":2,"description":"acc booking","linkman":"Alice","email":"alice@example.com","telephone":"13800000000"}')
BOOK_ID=$(printf '%s' "$BOOK" | jget .id)
[ "$BOOK_ID" != null ] && echo "ok   缺货登记 id=$BOOK_ID" || { echo "FAIL booking: $BOOK"; FAILED=$((FAILED+1)); }
check "重复登记 409" "409" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/me/bookings" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"goods_id":14,"goods_number":2}')"
check "删除登记 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X DELETE "$BASE_URL/api/v1/me/bookings/$BOOK_ID" "${AUTH[@]}")"

echo "=== 评论 / 留言 ==="
CMT=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/goods/12/comments" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"content":"acc comment"}')
CMT_ID=$(printf '%s' "$CMT" | jget .id)
[ "$CMT_ID" != null ] && echo "ok   评论 id=$CMT_ID" || { echo "FAIL comment: $CMT"; FAILED=$((FAILED+1)); }
check "商品评论列表" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/goods/12/comments")"
check "我的评论" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/me/comments" "${AUTH[@]}")"
check "删除评论 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X DELETE "$BASE_URL/api/v1/me/comments/$CMT_ID" "${AUTH[@]}")"
MSG=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/messages" -H 'Content-Type: application/json' \
  -d "{\"username\":\"acc visitor\",\"email\":\"visitor-$STAMP@example.test\",\"type\":0,\"title\":\"acc message\",\"content\":\"请问何时发货？\"}")
MSG_ID=$(printf '%s' "$MSG" | jget .id)
[ "$MSG_ID" != null ] && echo "ok   留言 id=$MSG_ID" || { echo "FAIL message: $MSG"; FAILED=$((FAILED+1)); }
check "留言列表(匿名)" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/messages")"
check "空内容留言 400" "400" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/messages" -H 'Content-Type: application/json' -d '{"username":"x","email":"x@example.test","type":0,"title":"t","content":""}')"
LOGINMSG=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/messages" "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "{\"username\":\"acc\",\"email\":\"$MAIL\",\"type\":0,\"title\":\"mine\",\"content\":\"my own message\"}")
MYMSG_ID=$(printf '%s' "$LOGINMSG" | jget .id)
check "我的留言" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/me/messages" "${AUTH[@]}")"
check "删除自己留言 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X DELETE "$BASE_URL/api/v1/me/messages/$MYMSG_ID" "${AUTH[@]}")"

echo "=== 投票 / 广告 / 团购 / 推广 / 订阅 ==="
check "当前投票" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/votes/current")"
VOTE_ID=$("${CURL[@]}" "$BASE_URL/api/v1/votes/current" | jget .vote.vote_id)
if [ "$VOTE_ID" != null ]; then
  check "投票响应 2xx/409" "true" "$(code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/votes/$VOTE_ID/responses" -H 'Content-Type: application/json' -d '{"option_ids":[1]}'); [[ "$code" == 204 || "$code" == 409 ]] && echo true || echo "code=$code")"
fi
check "广告点击" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/ads/1/click" -H 'Content-Type: application/json' -d '{"referer":"acc.example"}')"
check "我的团购" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/me/group-buys" "${AUTH[@]}")"
check "我的推广" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/me/affiliate?page=1&page_size=20" "${AUTH[@]}")"
check "订阅请求 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/newsletter-subscriptions" -H 'Content-Type: application/json' -d "{\"email\":\"newsletter-$STAMP@example.test\"}")"
NTOKEN=$("$SQLITE" "$DB" "SELECT json_extract(payload,'\$.token') FROM ecs_email_outbox WHERE template_name='newsletter_subscribe' ORDER BY message_id DESC LIMIT 1")
check "订阅确认 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/newsletter-subscriptions/confirm" -H 'Content-Type: application/json' -d "{\"token\":\"$NTOKEN\"}")"
check "退订请求 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X DELETE "$BASE_URL/api/v1/newsletter-subscriptions" -H 'Content-Type: application/json' -d "{\"email\":\"newsletter-$STAMP@example.test\"}")"
UTOKEN=$("$SQLITE" "$DB" "SELECT json_extract(payload,'\$.token') FROM ecs_email_outbox WHERE template_name='newsletter_unsubscribe' ORDER BY message_id DESC LIMIT 1")
check "退订确认 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/newsletter-unsubscriptions/confirm" -H 'Content-Type: application/json' -d "{\"token\":\"$UTOKEN\"}")"

echo "=== 密码重置与改密（最后执行） ==="
check "请求重置 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/password-resets" -H 'Content-Type: application/json' -d "{\"email\":\"$MAIL\"}")"
RTOKEN=$("$SQLITE" "$DB" "SELECT json_extract(payload,'\$.token') FROM ecs_email_outbox WHERE template_name='password_reset' ORDER BY message_id DESC LIMIT 1")
check "确认重置 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/password-resets/confirm" -H 'Content-Type: application/json' -d "{\"token\":\"$RTOKEN\",\"new_password\":\"reset-$PASS1\"}")"
LOGIN=$("${CURL[@]}" -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$USER\",\"password\":\"reset-$PASS1\"}")
TOKEN2=$(printf '%s' "$LOGIN" | jget .access_token)
[ "$TOKEN2" != null ] && echo "ok   新密码登录" || { echo "FAIL login: $LOGIN"; FAILED=$((FAILED+1)); }
AUTH2=(-H "Authorization: Bearer $TOKEN2")
check "旧 token 已失效 401" "401" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/me" "${AUTH[@]}")"
check "改密 2xx" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X PATCH "$BASE_URL/api/v1/me/password" "${AUTH2[@]}" -H 'Content-Type: application/json' -d "{\"current_password\":\"reset-$PASS1\",\"new_password\":\"new-correct-horse-battery\"}")"
check "改密后旧 token 失效 401" "401" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/me" "${AUTH2[@]}")"
check "新密码可登录" "2" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' -d "{\"username\":\"$USER\",\"password\":\"new-correct-horse-battery\"}")"

echo
if [ "$FAILED" = "0" ]; then echo "ALL PASS"; else echo "$FAILED 项失败"; exit 1; fi
