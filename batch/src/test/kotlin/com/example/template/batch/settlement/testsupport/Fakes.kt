package com.example.template.batch.settlement.testsupport

import arrow.core.Either
import com.example.template.application.port.SettlementFilePort
import com.example.template.application.port.TaskCallbackError
import com.example.template.application.port.TaskCallbackPort
import com.example.template.application.port.TaskToken
import com.example.template.application.settlement.ReconcileSettlementRecord
import com.example.template.domain.error.DomainError
import com.example.template.domain.error.SettlementError
import com.example.template.domain.settlement.ReconciliationOutcome
import com.example.template.domain.settlement.SettlementRecord
import java.time.LocalDate

/**
 * :batch は :adapter-persistence / :adapter-messaging のどちらにも依存しないため、
 * ジョブのエンドツーエンドテストでは :application が定義するポート/ユースケースの実装を
 * この Fake で代替する (:application のテストが FakeOrderRepository 等を使うのと同じ考え方)。
 */
class FakeSettlementFilePort(
    private val recordsByDate: Map<LocalDate, List<SettlementRecord>>,
) : SettlementFilePort {
    override suspend fun readRecordsFor(date: LocalDate): Either<SettlementError, List<SettlementRecord>> =
        recordsByDate[date]?.let { Either.Right(it) }
            ?: Either.Left(SettlementError.InfrastructureFailure("no test fixture registered for $date"))
}

/**
 * 実際の ReconcileSettlementService は OrderRepository 等の永続化ポートを必要とするため、
 * ジョブの統合テストでは orderId ごとに固定の判定結果を返す Fake に差し替える。
 * (判定ロジックそのものの正しさは domain/ReconciliationTest と
 * application/ReconcileSettlementServiceTest が別途検証済み。ここで検証したいのは
 * 「バッチのチャンク処理が Either の各分岐を正しく skip-and-report できるか」である。)
 */
class FakeReconcileSettlementRecord(
    private val outcomesByOrderId: Map<String, Either<SettlementError, ReconciliationOutcome>>,
) : ReconcileSettlementRecord {
    override suspend fun invoke(record: SettlementRecord): Either<SettlementError, ReconciliationOutcome> =
        outcomesByOrderId[record.orderId.value]
            ?: Either.Left(SettlementError.InfrastructureFailure("no test fixture registered for order ${record.orderId.value}"))
}

/** afterJob が実際に何を送信したかを記録するだけの TaskCallbackPort Fake。 */
class RecordingTaskCallbackPort : TaskCallbackPort {
    var lastSuccess: Pair<TaskToken, Any?>? = null
        private set
    var lastFailure: Pair<TaskToken, DomainError>? = null
        private set

    override suspend fun <A> notifySuccess(
        taskToken: TaskToken,
        payload: A,
    ): Either<TaskCallbackError, Unit> {
        lastSuccess = taskToken to payload
        return Either.Right(Unit)
    }

    override suspend fun notifyFailure(
        taskToken: TaskToken,
        error: DomainError,
    ): Either<TaskCallbackError, Unit> {
        lastFailure = taskToken to error
        return Either.Right(Unit)
    }
}
