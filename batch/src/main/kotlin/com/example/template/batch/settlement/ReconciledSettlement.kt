package com.example.template.batch.settlement

import com.example.template.domain.settlement.ReconciliationOutcome
import com.example.template.domain.settlement.SettlementRecord

/**
 * [SettlementItemProcessor] が1レコード分の消込判定に成功した際に、元の [SettlementRecord] と
 * その判定結果 [ReconciliationOutcome] をペアにして [SettlementItemWriter] に渡すための型。
 *
 * :application の ReconcileSettlementRecord は判定結果 (ReconciliationOutcome) しか返さないが、
 * SettlementItemWriter が settlements テーブルへ書き込む際には元レコードの金額・通貨・
 * プロバイダ取引ID等も必要になるため、ここで束ねておく。
 */
data class ReconciledSettlement(
    val record: SettlementRecord,
    val outcome: ReconciliationOutcome,
)
