package com.example.template.domain.order

import arrow.core.Either
import arrow.core.raise.either
import com.example.template.domain.error.OrderError
import com.example.template.domain.shared.Money
import java.time.Instant

/*
 * 注文の状態遷移関数群。
 *
 * ##### この5行が本モジュールで最も重要な設計判断
 * どの関数の `when (status) { ... }` にも `else` 分岐を書かない。OrderStatus は
 * sealed interface なので、Kotlin コンパイラは `when` が全サブタイプを網羅しているかを
 * 静的に検証する。つまり、将来 OrderStatus に9番目の状態 (例えば "返品受付中") を
 * 追加した瞬間、このファイルの7つの遷移関数「すべて」がコンパイルエラーになる。
 * 「新しい状態からの遷移/への遷移をどう扱うか」を実装者が定義し忘れることが
 * 構造的に不可能になる、というのがこの書き方の狙い。
 * どこか1箇所にでも `else -> raise(...)` を書いてしまうと、この安全網は消える。
 */

/** Draft -> PendingPayment。 */
fun Order.submitForPayment(): Either<OrderError, Order> =
    either {
        val next =
            when (status) {
                is OrderStatus.Draft -> OrderStatus.PendingPayment
                is OrderStatus.PendingPayment -> raise(OrderError.InvalidTransition(status, "submitForPayment"))
                is OrderStatus.Paid -> raise(OrderError.InvalidTransition(status, "submitForPayment"))
                is OrderStatus.Fulfilling -> raise(OrderError.InvalidTransition(status, "submitForPayment"))
                is OrderStatus.Shipped -> raise(OrderError.InvalidTransition(status, "submitForPayment"))
                is OrderStatus.Delivered -> raise(OrderError.InvalidTransition(status, "submitForPayment"))
                is OrderStatus.Cancelled -> raise(OrderError.InvalidTransition(status, "submitForPayment"))
                is OrderStatus.Refunded -> raise(OrderError.InvalidTransition(status, "submitForPayment"))
            }
        copy(status = next)
    }

/** PendingPayment -> Paid(paidAt)。決済プロバイダから支払完了通知を受けた際に呼ぶ。 */
fun Order.submitPayment(paidAt: Instant): Either<OrderError, Order> =
    either {
        val next =
            when (status) {
                is OrderStatus.Draft -> raise(OrderError.InvalidTransition(status, "submitPayment"))
                is OrderStatus.PendingPayment -> OrderStatus.Paid(paidAt)
                is OrderStatus.Paid -> raise(OrderError.InvalidTransition(status, "submitPayment"))
                is OrderStatus.Fulfilling -> raise(OrderError.InvalidTransition(status, "submitPayment"))
                is OrderStatus.Shipped -> raise(OrderError.InvalidTransition(status, "submitPayment"))
                is OrderStatus.Delivered -> raise(OrderError.InvalidTransition(status, "submitPayment"))
                is OrderStatus.Cancelled -> raise(OrderError.InvalidTransition(status, "submitPayment"))
                is OrderStatus.Refunded -> raise(OrderError.InvalidTransition(status, "submitPayment"))
            }
        copy(status = next)
    }

/**
 * 外部決済を呼ぶ前に、現在の状態が支払い可能であることを検証して請求額を返す。
 * 状態検証を [submitPayment] より後に遅らせると、遷移不能な注文へ実際の請求を行い得るため、
 * PayOrderService の準備フェーズからこの関数を使用する。
 */
fun Order.paymentDue(): Either<OrderError, Money> =
    either {
        when (status) {
            is OrderStatus.Draft -> raise(OrderError.InvalidTransition(status, "submitPayment"))
            is OrderStatus.PendingPayment -> total().mapLeft { OrderError.InvalidOrderLine(it.message) }.bind()
            is OrderStatus.Paid -> raise(OrderError.InvalidTransition(status, "submitPayment"))
            is OrderStatus.Fulfilling -> raise(OrderError.InvalidTransition(status, "submitPayment"))
            is OrderStatus.Shipped -> raise(OrderError.InvalidTransition(status, "submitPayment"))
            is OrderStatus.Delivered -> raise(OrderError.InvalidTransition(status, "submitPayment"))
            is OrderStatus.Cancelled -> raise(OrderError.InvalidTransition(status, "submitPayment"))
            is OrderStatus.Refunded -> raise(OrderError.InvalidTransition(status, "submitPayment"))
        }
    }

/** Paid -> Fulfilling(startedAt)。倉庫でピッキング・梱包に着手した際に呼ぶ。 */
fun Order.startFulfilling(startedAt: Instant): Either<OrderError, Order> =
    either {
        val next =
            when (status) {
                is OrderStatus.Draft -> raise(OrderError.InvalidTransition(status, "startFulfilling"))
                is OrderStatus.PendingPayment -> raise(OrderError.InvalidTransition(status, "startFulfilling"))
                is OrderStatus.Paid -> OrderStatus.Fulfilling(startedAt)
                is OrderStatus.Fulfilling -> raise(OrderError.InvalidTransition(status, "startFulfilling"))
                is OrderStatus.Shipped -> raise(OrderError.InvalidTransition(status, "startFulfilling"))
                is OrderStatus.Delivered -> raise(OrderError.InvalidTransition(status, "startFulfilling"))
                is OrderStatus.Cancelled -> raise(OrderError.InvalidTransition(status, "startFulfilling"))
                is OrderStatus.Refunded -> raise(OrderError.InvalidTransition(status, "startFulfilling"))
            }
        copy(status = next)
    }

/** Fulfilling -> Shipped(trackingNumber)。配送業者に引き渡した際に呼ぶ。 */
fun Order.ship(trackingNumber: String): Either<OrderError, Order> =
    either {
        val next =
            when (status) {
                is OrderStatus.Draft -> raise(OrderError.InvalidTransition(status, "ship"))
                is OrderStatus.PendingPayment -> raise(OrderError.InvalidTransition(status, "ship"))
                is OrderStatus.Paid -> raise(OrderError.InvalidTransition(status, "ship"))
                is OrderStatus.Fulfilling -> OrderStatus.Shipped(trackingNumber)
                is OrderStatus.Shipped -> raise(OrderError.InvalidTransition(status, "ship"))
                is OrderStatus.Delivered -> raise(OrderError.InvalidTransition(status, "ship"))
                is OrderStatus.Cancelled -> raise(OrderError.InvalidTransition(status, "ship"))
                is OrderStatus.Refunded -> raise(OrderError.InvalidTransition(status, "ship"))
            }
        copy(status = next)
    }

/** Shipped -> Delivered(deliveredAt)。顧客への配達完了を確認した際に呼ぶ。 */
fun Order.deliver(deliveredAt: Instant): Either<OrderError, Order> =
    either {
        val next =
            when (status) {
                is OrderStatus.Draft -> raise(OrderError.InvalidTransition(status, "deliver"))
                is OrderStatus.PendingPayment -> raise(OrderError.InvalidTransition(status, "deliver"))
                is OrderStatus.Paid -> raise(OrderError.InvalidTransition(status, "deliver"))
                is OrderStatus.Fulfilling -> raise(OrderError.InvalidTransition(status, "deliver"))
                is OrderStatus.Shipped -> OrderStatus.Delivered(deliveredAt)
                is OrderStatus.Delivered -> raise(OrderError.InvalidTransition(status, "deliver"))
                is OrderStatus.Cancelled -> raise(OrderError.InvalidTransition(status, "deliver"))
                is OrderStatus.Refunded -> raise(OrderError.InvalidTransition(status, "deliver"))
            }
        copy(status = next)
    }

/**
 * {Draft, PendingPayment, Paid} -> Cancelled(reason)。
 * 出荷準備に着手した後 (Fulfilling 以降) はキャンセル不可というのが業務ルール。
 */
fun Order.cancel(reason: String): Either<OrderError, Order> =
    either {
        val next =
            when (status) {
                is OrderStatus.Draft -> OrderStatus.Cancelled(reason)
                is OrderStatus.PendingPayment -> OrderStatus.Cancelled(reason)
                is OrderStatus.Paid -> OrderStatus.Cancelled(reason)
                is OrderStatus.Fulfilling -> raise(OrderError.InvalidTransition(status, "cancel"))
                is OrderStatus.Shipped -> raise(OrderError.InvalidTransition(status, "cancel"))
                is OrderStatus.Delivered -> raise(OrderError.InvalidTransition(status, "cancel"))
                is OrderStatus.Cancelled -> raise(OrderError.InvalidTransition(status, "cancel"))
                is OrderStatus.Refunded -> raise(OrderError.InvalidTransition(status, "cancel"))
            }
        copy(status = next)
    }

/**
 * {Paid, Delivered} -> Refunded(refundedAt, amount)。
 * 支払い後であればキャンセル済みになる前 (Paid) でも配達後 (Delivered、返品対応) でも返金可能とする。
 */
fun Order.refund(
    refundedAt: Instant,
    amount: Money,
): Either<OrderError, Order> =
    either {
        val next =
            when (status) {
                is OrderStatus.Draft -> raise(OrderError.InvalidTransition(status, "refund"))
                is OrderStatus.PendingPayment -> raise(OrderError.InvalidTransition(status, "refund"))
                is OrderStatus.Paid -> OrderStatus.Refunded(refundedAt, amount)
                is OrderStatus.Fulfilling -> raise(OrderError.InvalidTransition(status, "refund"))
                is OrderStatus.Shipped -> raise(OrderError.InvalidTransition(status, "refund"))
                is OrderStatus.Delivered -> OrderStatus.Refunded(refundedAt, amount)
                is OrderStatus.Cancelled -> raise(OrderError.InvalidTransition(status, "refund"))
                is OrderStatus.Refunded -> raise(OrderError.InvalidTransition(status, "refund"))
            }
        copy(status = next)
    }
