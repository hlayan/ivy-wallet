package com.ivy.core.domain.action.calculate

import arrow.core.getOrElse
import arrow.core.nonEmptyListOf
import com.ivy.core.domain.action.FlowAction
import com.ivy.core.domain.action.exchange.ExchangeRatesFlow
import com.ivy.core.domain.pure.exchange.exchange
import com.ivy.core.domain.pure.transaction.sumTransactions
import com.ivy.data.CurrencyCode
import com.ivy.data.Value
import com.ivy.data.exchange.ExchangeRatesData
import com.ivy.data.transaction.Transaction
import com.ivy.data.transaction.TrnPurpose.TransferFrom
import com.ivy.data.transaction.TrnPurpose.TransferTo
import com.ivy.data.transaction.TrnType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Calculates [Stats] (income, expense, counts, balance) for a list of [Transaction]
 * converted in a **outputCurrency** of your choice.
 */
class CalculateFlow @Inject constructor(
    private val exchangeRatesFlow: ExchangeRatesFlow,
) : FlowAction<CalculateFlow.Input, Stats>() {
    /**
     * @param trns transactions for which the stats will be calculated.
     * Transfers may be excluded depending on [includeTransfers].
     * @param includeTransfers whether to include transfer transactions in the calculation.
     * - **false** to exclude transactions with purpose [TransferFrom] and [TransferTo]
     * - **true** to include all [trns] in the calculation
     * @param outputCurrency use **null** for base currency.
     */
    data class Input(
        val trns: List<Transaction>,
        val includeTransfers: Boolean,
        val outputCurrency: CurrencyCode? = null,
    )

    override fun Input.createFlow(): Flow<Stats> = exchangeRatesFlow().map { rates ->
        calculate(rates = rates)
    }

    suspend fun Input.calculate(
        rates: ExchangeRatesData,
    ): Stats {
        val outputCurrency = this.outputCurrency ?: rates.baseCurrency
        val res = sumTransactions(
            transactions = trns.filter {
                if (!includeTransfers) {
                    // don't include transfer transactions
                    when (it.purpose) {
                        TransferFrom, TransferTo -> false
                        else -> true
                    }
                } else true
            },
            selectors = nonEmptyListOf(
                ::income,
                ::expense,
                ::countIncome,
                ::countExpense,
            ),
            arg = SumArg(
                rates = rates,
                outputCurrency = outputCurrency,
            )
        )

        val income = res[0]
        val expense = res[1]

        return Stats(
            balance = Value(amount = income - expense, currency = outputCurrency),
            income = Value(amount = income, currency = outputCurrency),
            expense = Value(amount = expense, currency = outputCurrency),
            incomesCount = res[2].toInt(),
            expensesCount = res[3].toInt(),
        )
    }

    private suspend fun income(
        trn: Transaction, arg: SumArg
    ): Double = when (trn.type) {
        TrnType.Income -> trnAmountInCurrency(trn, arg)
        else -> 0.0
    }

    private suspend fun expense(
        trn: Transaction, arg: SumArg
    ): Double = when (trn.type) {
        TrnType.Expense -> trnAmountInCurrency(trn, arg)
        else -> 0.0
    }

    private suspend fun trnAmountInCurrency(
        trn: Transaction,
        arg: SumArg,
    ): Double = exchange(
        ratesData = arg.rates,
        from = trn.value.currency,
        to = arg.outputCurrency,
        amount = trn.value.amount,
    ).getOrElse { 0.0 }

    @Suppress("RedundantSuspendModifier", "UNUSED_PARAMETER")
    private suspend fun countIncome(
        trn: Transaction, arg: SumArg
    ): Double = when (trn.type) {
        TrnType.Income -> 1.0
        else -> 0.0
    }

    @Suppress("RedundantSuspendModifier", "UNUSED_PARAMETER")
    private suspend fun countExpense(
        trn: Transaction, arg: SumArg
    ): Double = when (trn.type) {
        TrnType.Expense -> 1.0
        else -> 0.0
    }

    private data class SumArg(
        val rates: ExchangeRatesData,
        val outputCurrency: CurrencyCode,
    )
}