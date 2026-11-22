package com.facturastock.app.feature.debtors

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtDetail
import com.facturastock.app.domain.model.DebtLine
import com.facturastock.app.domain.model.DebtListSnapshot
import com.facturastock.app.domain.model.DebtPayment
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.DebtSummary
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.DebtPaymentId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.repository.DebtRepository
import com.facturastock.app.domain.repository.RecordDebtPaymentCommand
import com.facturastock.app.domain.repository.RecordDebtPaymentResult
import com.facturastock.app.domain.usecase.ObserveDebtDetailUseCase
import com.facturastock.app.domain.usecase.ObserveDebtsUseCase
import com.facturastock.app.domain.usecase.VoidSaleUseCase
import com.facturastock.app.domain.usecase.RecordDebtPaymentUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebtorsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val configuration = FakeAppConfigurationRepository()
    private val repository = RecordingDebtRepository()

    @Test
    fun `only visible lists observe debts and returning keeps the query and filter`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            val initial = summary(DEBT_ID, money("30.00")).copy(debtorName = "Cliente A")
            val handle = SavedStateHandle(mapOf("debtors.filter" to "ALL", "debtors.query" to "cliente"))
            repository.all.value = listOf(initial)
            val viewModel = createViewModel(savedStateHandle = handle, listVisible = false)
            runCurrent()
            assertEquals(0, repository.allReads)

            viewModel.listStarted()
            viewModel.listStarted()
            viewModel.listStopped()
            runCurrent()
            assertEquals(1, repository.allReads)
            assertEquals(1, repository.all.subscriptionCount.value)
            assertEquals(listOf(initial), viewModel.uiState.value.debts)

            viewModel.listStopped()
            runCurrent()
            assertEquals(0, repository.all.subscriptionCount.value)
            assertTrue(viewModel.uiState.value.debts.isEmpty())
            assertNull(viewModel.uiState.value.totalOpenBalance)
            val updated = initial.copy(balance = money("10.00"), version = 2L)
            repository.all.value = listOf(updated)
            runCurrent()
            assertTrue(viewModel.uiState.value.isLoading)

            viewModel.listStarted()
            runCurrent()
            assertEquals(2, repository.allReads)
            assertEquals(1, repository.all.subscriptionCount.value)
            assertEquals(listOf(updated), viewModel.uiState.value.debts)
            assertEquals(updated.balance, viewModel.uiState.value.totalOpenBalance)
            assertEquals("cliente", viewModel.uiState.value.query)
            assertEquals(DebtorsContract.StatusFilter.ALL, viewModel.uiState.value.statusFilter)
        }

    @Test
    fun `list never presents a misleading total when open debts use different currencies`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            val debts = listOf(
                summary(debtId = DEBT_ID, balance = money("20.00")),
                summary(
                    debtId = SECOND_DEBT_ID,
                    balance = Money.fromMajor("10.00", CurrencyCode.of("USD")),
                    originalAmount = Money.fromMajor("10.00", CurrencyCode.of("USD")),
                ),
            )
            repository.open.value = DebtListSnapshot(debts)

            val viewModel = createViewModel()
            runCurrent()

            assertEquals(2, viewModel.uiState.value.debts.size)
            assertNull(viewModel.uiState.value.totalOpenBalance)
        }

    @Test
    fun `opening asks for pending only and typing filters literal text without changing the total`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            val shop = summary(DEBT_ID, money("20.00")).copy(debtorName = "Tienda 50%_Ávila")
            val other = summary(SECOND_DEBT_ID, money("8.00")).copy(debtorName = "Tienda 50xxÁvila")
            repository.open.value = DebtListSnapshot(listOf(shop, other))

            val viewModel = createViewModel()
            runCurrent()
            assertEquals(listOf(shop, other), viewModel.uiState.value.debts)

            viewModel.onAction(DebtorsContract.Action.SearchChanged("  %_ÁV  "))
            runCurrent()
            assertEquals(listOf(shop), viewModel.uiState.value.debts)
            assertEquals(money("28.00"), viewModel.uiState.value.totalOpenBalance)
            viewModel.onAction(DebtorsContract.Action.SearchChanged("zzz"))
            runCurrent()
            assertTrue(viewModel.uiState.value.debts.isEmpty())
            assertEquals(money("28.00"), viewModel.uiState.value.totalOpenBalance)
            assertEquals(1, repository.openReads)
            assertEquals(0, repository.allReads)
        }

    @Test
    fun `paid all and pending filters use the correct source after restoring the screen`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            val open = summary(DEBT_ID, money("7.00"))
            val paid = summary(SECOND_DEBT_ID, money("9.00")).copy(
                status = DebtStatus.PAID, balance = money("0.00"), paidAt = NOW,
            )
            repository.open.value = DebtListSnapshot(listOf(open))
            repository.all.value = listOf(open, paid)
            val handle = SavedStateHandle()
            val viewModel = createViewModel(savedStateHandle = handle)
            runCurrent()

            viewModel.onAction(DebtorsContract.Action.StatusFilterChanged(DebtorsContract.StatusFilter.PAID))
            runCurrent()
            assertEquals(listOf(paid), viewModel.uiState.value.debts)
            assertEquals(open.balance, viewModel.uiState.value.totalOpenBalance)

            val savedValues = handle.keys().associateWith { key -> handle.get<Any?>(key) }
            val restored = createViewModel(savedStateHandle = SavedStateHandle(savedValues))
            runCurrent()
            assertEquals(DebtorsContract.StatusFilter.PAID, restored.uiState.value.statusFilter)
            assertEquals(listOf(paid), restored.uiState.value.debts)
            assertEquals(1, repository.openReads)
            restored.onAction(DebtorsContract.Action.StatusFilterChanged(DebtorsContract.StatusFilter.ALL))
            runCurrent()
            assertEquals(listOf(open, paid), restored.uiState.value.debts)
            restored.onAction(DebtorsContract.Action.StatusFilterChanged(DebtorsContract.StatusFilter.OPEN))
            runCurrent()
            assertEquals(listOf(open), restored.uiState.value.debts)
            assertEquals(2, repository.openReads)
        }

    @Test
    fun `paying the last debt preserves its currency while an empty business has no total`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            val usd = CurrencyCode.of("USD")
            val debt = summary(DEBT_ID, Money.fromMajor("9.50", usd))
            repository.open.value = DebtListSnapshot(listOf(debt))
            val viewModel = createViewModel()
            runCurrent()
            assertEquals(debt.balance, viewModel.uiState.value.totalOpenBalance)
            repository.open.value = DebtListSnapshot(emptyList(), fallbackCurrency = usd)
            runCurrent()
            assertTrue(viewModel.uiState.value.debts.isEmpty())
            assertEquals(Money.zero(usd), viewModel.uiState.value.totalOpenBalance)
            repository.open.value = DebtListSnapshot(emptyList())
            runCurrent()
            assertNull(viewModel.uiState.value.totalOpenBalance)
            assertEquals(0, repository.allReads)
        }

    @Test
    fun `recorded payment closes editor emits saved and remains live from detail flow`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            val original = summary(debtId = DEBT_ID, balance = money("20.00"))
            val updated = original.copy(
                balance = money("15.00"),
                version = 2L,
                updatedAt = NOW.plusSeconds(1),
            )
            val payment = payment(balanceAfter = updated.balance)
            repository.detail.value = detail(original)
            repository.nextPaymentResult = RecordDebtPaymentResult.Recorded(updated, payment)
            val viewModel = createViewModel(DEBT_ID)
            viewModel.listStopped()
            runCurrent()
            assertEquals(1, repository.detail.subscriptionCount.value)

            viewModel.onAction(DebtorsContract.Action.PartialPaymentRequested)
            runCurrent()
            viewModel.onAction(DebtorsContract.Action.PaymentAmountChanged("5.00"))
            runCurrent()
            val saved = async { viewModel.effects.first() }
            viewModel.onAction(DebtorsContract.Action.PaymentConfirmed)
            runCurrent()

            assertEquals(DebtorsContract.Effect.PaymentSaved, saved.await())
            assertNull(viewModel.uiState.value.paymentEditor)
            assertFalse(viewModel.uiState.value.isSavingPayment)
            assertEquals(money("5.00"), repository.paymentCommands.single().amount)
            assertEquals(DebtPaymentMethod.CASH, repository.paymentCommands.single().method)

            repository.detail.value = detail(updated, payments = listOf(payment))
            runCurrent()

            assertEquals(money("15.00"), viewModel.uiState.value.detail?.debt?.balance)
            assertEquals(listOf(payment), viewModel.uiState.value.detail?.payments)
        }

    @Test
    fun `online required keeps payment editor open and retries with the same occurredAt`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            repository.detail.value = detail(summary(debtId = DEBT_ID, balance = money("20.00")))
            repository.nextPaymentResult = RecordDebtPaymentResult.OnlineRequired
            val viewModel = createViewModel(DEBT_ID)
            runCurrent()

            viewModel.onAction(DebtorsContract.Action.PartialPaymentRequested)
            runCurrent()
            viewModel.onAction(DebtorsContract.Action.PaymentAmountChanged("4.00"))
            runCurrent()
            viewModel.onAction(DebtorsContract.Action.PaymentConfirmed)
            runCurrent()

            assertEquals(DebtorsContract.Failure.ONLINE_REQUIRED, viewModel.uiState.value.failure)
            assertTrue(viewModel.uiState.value.paymentEditor != null)
            assertFalse(viewModel.uiState.value.isSavingPayment)
            assertEquals(NOW, repository.paymentCommands.single().occurredAt)

            viewModel.onAction(DebtorsContract.Action.PaymentConfirmed)
            runCurrent()

            assertEquals(2, repository.paymentCommands.size)
            assertEquals(
                repository.paymentCommands.first().occurredAt,
                repository.paymentCommands.last().occurredAt,
            )
        }

    private suspend fun activateBusiness() {
        configuration.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
    }

    private fun createViewModel(
        debtId: DebtId? = null,
        savedStateHandle: SavedStateHandle = SavedStateHandle(
            debtId?.let { mapOf(RouteArgumentKeys.DEBT_ID to it.value) }.orEmpty(),
        ),
        listVisible: Boolean = true,
    ) = DebtorsViewModel(
        savedStateHandle = savedStateHandle,
        observeDebts = ObserveDebtsUseCase(configuration, repository),
        observeDebtDetail = ObserveDebtDetailUseCase(configuration, repository),
        recordPayment = RecordDebtPaymentUseCase(configuration, repository),
        clock = AppClock { NOW },
        dispatcherProvider = TestDispatcherProvider(mainDispatcherRule.dispatcher),
        voidSale = VoidSaleUseCase(configuration, FakeDebtVoidRepository()),
        configuration = configuration,
    ).also { if (listVisible) it.listStarted() }

    private class RecordingDebtRepository : DebtRepository {
        val open = MutableStateFlow(DebtListSnapshot(emptyList()))
        var openReads = 0
        var allReads = 0
        val all = MutableStateFlow<List<DebtSummary>>(emptyList())
        val detail = MutableStateFlow<DebtDetail?>(null)
        val paymentCommands = mutableListOf<RecordDebtPaymentCommand>()
        var nextPaymentResult: RecordDebtPaymentResult = RecordDebtPaymentResult.OnlineRequired

        override fun observeOpen(businessId: BusinessId): Flow<List<DebtSummary>> = open.map { it.debts }

        override fun observeOpenSnapshot(businessId: BusinessId): Flow<DebtListSnapshot> {
            openReads++
            return open
        }

        override fun observeAll(businessId: BusinessId): Flow<List<DebtSummary>> {
            allReads++
            return all
        }

        override fun observeDetail(
            businessId: BusinessId,
            debtId: DebtId,
        ): Flow<DebtDetail?> = detail

        override suspend fun recordPayment(
            businessId: BusinessId,
            command: RecordDebtPaymentCommand,
        ): RecordDebtPaymentResult {
            paymentCommands += command
            return nextPaymentResult
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-31T15:00:00Z")
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val BUSINESS_ID = BusinessId.from(uuid(1))
        val DEBT_ID = DebtId.from(uuid(2))
        val SECOND_DEBT_ID = DebtId.from(uuid(3))
        val SALE_ID = SaleId.from(uuid(4))
        val SALE_LINE_ID = SaleLineId.from(uuid(5))
        val PRODUCT_ID = ProductId.from(uuid(6))
        val PAYMENT_ID = DebtPaymentId.from(uuid(7))

        fun uuid(value: Int): UUID = UUID(0L, value.toLong())

        fun money(value: String): Money = Money.fromMajor(value, PEN)

        fun summary(
            debtId: DebtId,
            balance: Money,
            originalAmount: Money = balance,
        ) = DebtSummary(
            debtId = debtId,
            businessId = BUSINESS_ID,
            saleId = SALE_ID,
            debtorName = if (debtId == DEBT_ID) "Ana Torres" else "Luis Rojas",
            originalAmount = originalAmount,
            balance = balance,
            status = DebtStatus.OPEN,
            lineCount = 1,
            dueAt = null,
            version = 1L,
            createdAt = NOW,
            updatedAt = NOW,
            paidAt = null,
        )

        fun detail(
            summary: DebtSummary,
            payments: List<DebtPayment> = emptyList(),
        ) = DebtDetail(
            debt = summary,
            lines = listOf(
                DebtLine(
                    saleLineId = SALE_LINE_ID,
                    productId = PRODUCT_ID,
                    productName = "Arroz",
                    barcode = "7751234567890",
                    quantity = Quantity.of("2"),
                    unitPrice = Money.fromMajor("10.00", summary.originalAmount.currency),
                    lineTotal = summary.originalAmount,
                ),
            ),
            payments = payments,
        )

        fun payment(balanceAfter: Money) = DebtPayment(
            paymentId = PAYMENT_ID,
            debtId = DEBT_ID,
            amount = money("5.00"),
            method = DebtPaymentMethod.CASH,
            note = null,
            reference = null,
            expectedDebtVersion = 1L,
            balanceAfter = balanceAfter,
            occurredAt = NOW,
            createdAt = NOW,
        )
    }
}
