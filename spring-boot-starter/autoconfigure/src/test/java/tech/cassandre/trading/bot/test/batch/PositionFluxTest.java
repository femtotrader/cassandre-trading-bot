package tech.cassandre.trading.bot.test.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import tech.cassandre.trading.bot.batch.TickerFlux;
import tech.cassandre.trading.bot.batch.TradeFlux;
import tech.cassandre.trading.bot.domain.Order;
import tech.cassandre.trading.bot.domain.Position;
import tech.cassandre.trading.bot.dto.market.TickerDTO;
import tech.cassandre.trading.bot.dto.position.PositionCreationResultDTO;
import tech.cassandre.trading.bot.dto.position.PositionDTO;
import tech.cassandre.trading.bot.dto.position.PositionRulesDTO;
import tech.cassandre.trading.bot.dto.trade.TradeDTO;
import tech.cassandre.trading.bot.dto.util.CurrencyAmountDTO;
import tech.cassandre.trading.bot.mock.batch.PositionFluxTestMock;
import tech.cassandre.trading.bot.repository.OrderRepository;
import tech.cassandre.trading.bot.repository.PositionRepository;
import tech.cassandre.trading.bot.repository.TradeRepository;
import tech.cassandre.trading.bot.service.PositionService;
import tech.cassandre.trading.bot.test.util.junit.BaseTest;
import tech.cassandre.trading.bot.test.util.junit.configuration.Configuration;
import tech.cassandre.trading.bot.test.util.junit.configuration.Property;
import tech.cassandre.trading.bot.test.util.strategies.TestableCassandreStrategy;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Optional;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD;
import static tech.cassandre.trading.bot.dto.position.PositionStatusDTO.CLOSED;
import static tech.cassandre.trading.bot.dto.position.PositionStatusDTO.CLOSING;
import static tech.cassandre.trading.bot.dto.position.PositionStatusDTO.OPENED;
import static tech.cassandre.trading.bot.dto.position.PositionStatusDTO.OPENING;
import static tech.cassandre.trading.bot.dto.trade.OrderTypeDTO.ASK;
import static tech.cassandre.trading.bot.dto.trade.OrderTypeDTO.BID;
import static tech.cassandre.trading.bot.util.parameters.ExchangeParameters.Rates.PARAMETER_EXCHANGE_RATE_TICKER;
import static tech.cassandre.trading.bot.util.parameters.ExchangeParameters.Rates.PARAMETER_EXCHANGE_RATE_TRADE;

@SpringBootTest
@DisplayName("Batch - Position flux")
@Configuration({
        @Property(key = PARAMETER_EXCHANGE_RATE_TICKER, value = "100"),
        @Property(key = PARAMETER_EXCHANGE_RATE_TRADE, value = "100")
})
@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)
@Import(PositionFluxTestMock.class)
public class PositionFluxTest extends BaseTest {

    @Autowired
    private TestableCassandreStrategy strategy;

    @Autowired
    private PositionService positionService;

    @Autowired
    private TickerFlux tickerFlux;

    @Autowired
    private TradeFlux tradeFlux;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Test
    @DisplayName("Check received data")
    public void checkReceivedData() {
        assertEquals(0, strategy.getPositionsUpdateReceived().size());

        System.out.println("==> " + strategy.getStrategyDTO());
        // =============================================================================================================
        // Creates position 1 - should be OPENING.
        final PositionCreationResultDTO position1Result = strategy.createPosition(cp1,
                new BigDecimal("10"),
                PositionRulesDTO.builder()
                        .stopGainPercentage(1000f)   // 1 000% max gain.
                        .stopLossPercentage(100f)    // 100% max lost.
                        .build());
        assertEquals("ORDER00010", position1Result.getPosition().getOpeningOrder().getOrderId());
        long position1Id = position1Result.getPosition().getId();
        int positionStatusUpdateIndex = 0;
        int positionUpdateIndex = 0;

        // onPositionUpdate - Position 1 should arrive (OPENING).
        await().untilAsserted(() -> assertEquals(1, strategy.getPositionsStatusUpdateReceived().size()));
        PositionDTO p = strategy.getPositionsStatusUpdateReceived().get(positionStatusUpdateIndex);
        assertNotNull(p);
        assertEquals(position1Id, p.getId());
        assertEquals(OPENING, p.getStatus());

        // onPosition - Position 1 should arrive (OPENING).
        await().untilAsserted(() -> assertEquals(1, strategy.getPositionsUpdateReceived().size()));
        p = strategy.getPositionsUpdateReceived().get(positionUpdateIndex);
        assertNotNull(p);
        assertEquals(position1Id, p.getId());
        assertEquals(OPENING, p.getStatus());

        // Checking what we have in database.
        assertEquals(1, positionRepository.count());
        assertEquals(1, strategy.getPositions().size());
        PositionDTO p1 = strategy.getPositions().get(position1Id);
        assertNotNull(p1);
        assertNotNull(strategy.getPositionById(position1Id));
        assertEquals(1L, p1.getId());
        assertEquals(OPENING, p1.getStatus());
        assertEquals(cp1, p1.getCurrencyPair());
        assertEquals(0, new BigDecimal("10").compareTo(p1.getAmount().getValue()));
        assertEquals(cp1.getBaseCurrency(), p1.getAmount().getCurrency());
        assertTrue(p1.getRules().isStopGainPercentageSet());
        assertEquals(1000f, p1.getRules().getStopGainPercentage());
        assertTrue(p1.getRules().isStopLossPercentageSet());
        assertEquals(100f, p1.getRules().getStopLossPercentage());
        assertEquals("ORDER00010", p1.getOpeningOrder().getOrderId());
        assertTrue(p1.getOpeningTrades().isEmpty());
        assertNull(p1.getClosingOrder());
        assertTrue(p1.getClosingTrades().isEmpty());
        assertNull(p1.getLowestPrice());
        assertNull(p1.getHighestPrice());
        assertNull(p1.getLatestPrice());
        assertNotNull(p1.getStrategy());
        assertEquals(1, p1.getStrategy().getId());
        assertEquals("01", p1.getStrategy().getStrategyId());

        // =============================================================================================================
        // Creates positions 2 - should be OPENING.
        final PositionCreationResultDTO position2Result = strategy.createPosition(cp2,
                new BigDecimal("0.0002"),
                PositionRulesDTO.builder()
                        .stopGainPercentage(10000000f)
                        .stopLossPercentage(10000000f)
                        .build());
        assertEquals("ORDER00020", position2Result.getPosition().getOpeningOrder().getOrderId());
        long position2Id = position2Result.getPosition().getId();
        positionStatusUpdateIndex++;
        positionUpdateIndex++;

        // onPositionUpdate - Position 2 should arrive (OPENING).
        await().untilAsserted(() -> assertEquals(2, strategy.getPositionsStatusUpdateReceived().size()));
        p = strategy.getPositionsStatusUpdateReceived().get(positionStatusUpdateIndex);
        assertNotNull(p);
        assertEquals(2, p.getId());
        assertEquals(OPENING, p.getStatus());

        // onPosition - Position 2 should arrive (OPENING).
        await().untilAsserted(() -> assertEquals(2, strategy.getPositionsUpdateReceived().size()));
        p = strategy.getPositionsUpdateReceived().get(positionUpdateIndex);
        assertNotNull(p);
        assertEquals(position2Id, p.getId());
        assertEquals(OPENING, p.getStatus());

        // Checking what we have in database.
        assertEquals(2, positionRepository.count());
        assertEquals(2, strategy.getPositions().size());
        PositionDTO p2 = strategy.getPositions().get(position2Id);
        assertNotNull(p2);
        assertNotNull(strategy.getPositionById(position2Id));
        assertEquals(2L, p2.getId());
        assertEquals(OPENING, p2.getStatus());
        assertEquals(cp2, p2.getCurrencyPair());
        assertEquals(0, new BigDecimal("0.0002").compareTo(p2.getAmount().getValue()));
        assertEquals(cp2.getBaseCurrency(), p2.getAmount().getCurrency());
        assertTrue(p2.getRules().isStopGainPercentageSet());
        assertEquals(10000000f, p2.getRules().getStopGainPercentage());
        assertTrue(p2.getRules().isStopLossPercentageSet());
        assertEquals(10000000f, p2.getRules().getStopLossPercentage());
        assertEquals("ORDER00020", p2.getOpeningOrder().getOrderId());
        assertTrue(p2.getOpeningTrades().isEmpty());
        assertNull(p2.getClosingOrder());
        assertTrue(p2.getClosingTrades().isEmpty());
        assertNull(p2.getLowestPrice());
        assertNull(p2.getHighestPrice());
        assertNull(p2.getLatestPrice());
        assertEquals(1, p1.getStrategy().getId());
        assertEquals("01", p1.getStrategy().getStrategyId());

        // =============================================================================================================
        // As the two trades expected by position 1 arrives, position 1 should now be OPENED.
        // 11 is before 1 to test the timestamp order of getOpenTrades & getCloseTrades.

        // First trade.
        tradeFlux.emitValue(TradeDTO.builder().tradeId("000011")
                .orderId("ORDER00010")
                .type(BID)
                .currencyPair(cp1)
                .amount(new CurrencyAmountDTO("4", cp1.getBaseCurrency()))
                .price(new CurrencyAmountDTO("0.03", cp1.getQuoteCurrency()))
                .timestamp(createZonedDateTime("02-02-2020"))
                .build());
        positionUpdateIndex++;
        // The same trade is emitted two times with an update (on time).
        tradeFlux.emitValue(TradeDTO.builder().tradeId("000011")
                .orderId("ORDER00010")
                .type(BID)
                .currencyPair(cp1)
                .amount(new CurrencyAmountDTO("4", cp1.getBaseCurrency()))
                .price(new CurrencyAmountDTO("0.03", cp1.getQuoteCurrency()))
                .timestamp(createZonedDateTime("02-02-2020"))
                .build());
        positionUpdateIndex++;

        // Second trade.
        tradeFlux.emitValue(TradeDTO.builder().tradeId("000001")
                .orderId("ORDER00010")
                .type(BID)
                .currencyPair(cp1)
                .amount(new CurrencyAmountDTO("6", cp1.getBaseCurrency()))
                .price(new CurrencyAmountDTO("0.03", cp1.getQuoteCurrency()))
                .timestamp(createZonedDateTime("01-01-2020"))
                .build());
        positionUpdateIndex++;
        // The same trade is emitted two times with an update (on time).
        tradeFlux.emitValue(TradeDTO.builder().tradeId("000001")
                .orderId("ORDER00010")
                .type(BID)
                .currencyPair(cp1)
                .amount(new CurrencyAmountDTO("6", cp1.getBaseCurrency()))
                .price(new CurrencyAmountDTO("0.03", cp1.getQuoteCurrency()))
                .timestamp(createZonedDateTime("01-01-2020"))
                .build());
        positionUpdateIndex++;

        // With the two trades emitted, status should change.
        positionStatusUpdateIndex++;

        // onPositionUpdate - Position 1 should change to OPENED.
        await().untilAsserted(() -> assertEquals(3, strategy.getPositionsStatusUpdateReceived().size()));
        p = strategy.getPositionsStatusUpdateReceived().get(positionStatusUpdateIndex);
        assertNotNull(p);
        assertEquals(position1Id, p.getId());
        assertEquals(OPENED, p.getStatus());

        // onPosition - 2 trades emitted 2 times so 4 updates (+2 already received for position opening).
        await().untilAsserted(() -> assertEquals(6, strategy.getPositionsUpdateReceived().size()));
        p = strategy.getPositionsUpdateReceived().get(positionUpdateIndex);
        assertNotNull(p);
        assertEquals(position1Id, p.getId());
        assertEquals(OPENED, p.getStatus());

        // Checking what we have in database.
        assertEquals(2, strategy.getPositions().size());
        p1 = strategy.getPositions().get(position1Id);
        assertNotNull(p1);
        assertEquals(1L, p1.getId());
        assertEquals(OPENED, p1.getStatus());
        assertEquals(cp1, p1.getCurrencyPair());
        assertEquals(0, new BigDecimal("10").compareTo(p1.getAmount().getValue()));
        assertEquals(cp1.getBaseCurrency(), p1.getAmount().getCurrency());
        assertTrue(p1.getRules().isStopGainPercentageSet());
        assertEquals(1000f, p1.getRules().getStopGainPercentage());
        assertTrue(p1.getRules().isStopLossPercentageSet());
        assertEquals(100f, p1.getRules().getStopLossPercentage());
        assertEquals("ORDER00010", p1.getOpeningOrder().getOrderId());
        assertEquals(2, p1.getOpeningTrades().size());
        assertNull(p1.getClosingOrder());
        assertTrue(p1.getClosingTrades().isEmpty());
        assertNull(p1.getLowestPrice());
        assertNull(p1.getHighestPrice());
        assertNull(p1.getLatestPrice());
        // Check trade orders.
        Iterator<TradeDTO> openTradesIterator = p1.getOpeningTrades().iterator();
        assertEquals("000001", openTradesIterator.next().getTradeId());
        assertEquals("000011", openTradesIterator.next().getTradeId());

        // Check if we don't have duplicated trades in database !
        assertEquals(2, tradeRepository.count());
        Optional<Order> order00010 = orderRepository.findByOrderId("ORDER00010");
        assertTrue(order00010.isPresent());
        assertEquals(2, order00010.get().getTrades().size());

        // =============================================================================================================
        // Test of tickers updating the position 1.

        // First ticker arrives (500% gain) - min, max and last gain should be set to that value.
        tickerFlux.emitValue(TickerDTO.builder().currencyPair(cp1).last(new BigDecimal("0.18")).build());
        positionUpdateIndex++;
        await().untilAsserted(() -> assertEquals(7, strategy.getPositionsUpdateReceived().size()));
        p = strategy.getPositionsUpdateReceived().get(positionUpdateIndex);
        assertEquals(position1Id, p.getId());
        assertEquals(0, new BigDecimal("0.18").compareTo(p.getLowestPrice().getValue()));
        assertEquals(0, new BigDecimal("0.18").compareTo(p.getHighestPrice().getValue()));
        assertEquals(0, new BigDecimal("0.18").compareTo(p.getLatestPrice().getValue()));

        // Second ticker arrives (100% gain) - min and last gain should be set to that value.
        tickerFlux.emitValue(TickerDTO.builder().currencyPair(cp1).last(new BigDecimal("0.06")).build());
        positionUpdateIndex++;
        await().untilAsserted(() -> assertEquals(8, strategy.getPositionsUpdateReceived().size()));
        p = strategy.getPositionsUpdateReceived().get(positionUpdateIndex);
        assertEquals(position1Id, p.getId());
        assertEquals(0, new BigDecimal("0.06").compareTo(p.getLowestPrice().getValue()));
        assertEquals(0, new BigDecimal("0.18").compareTo(p.getHighestPrice().getValue()));
        assertEquals(0, new BigDecimal("0.06").compareTo(p.getLatestPrice().getValue()));

        // Third ticker arrives (200% gain) - only last should change.
        tickerFlux.emitValue(TickerDTO.builder().currencyPair(cp1).last(new BigDecimal("0.09")).build());
        positionUpdateIndex++;
        await().untilAsserted(() -> assertEquals(9, strategy.getPositionsUpdateReceived().size()));
        p = strategy.getPositionsUpdateReceived().get(positionUpdateIndex);
        assertEquals(position1Id, p.getId());
        assertEquals(0, new BigDecimal("0.06").compareTo(p.getLowestPrice().getValue()));
        assertEquals(0, new BigDecimal("0.18").compareTo(p.getHighestPrice().getValue()));
        assertEquals(0, new BigDecimal("0.09").compareTo(p.getLatestPrice().getValue()));

        // Fourth ticker arrives (50% loss) - min and last gain should be set to that value.
        tickerFlux.emitValue(TickerDTO.builder().currencyPair(cp1).last(new BigDecimal("0.015")).build());
        positionUpdateIndex++;
        await().untilAsserted(() -> assertEquals(10, strategy.getPositionsUpdateReceived().size()));
        p = strategy.getPositionsUpdateReceived().get(positionUpdateIndex);
        assertEquals(position1Id, p.getId());
        assertEquals(0, new BigDecimal("0.015").compareTo(p.getLowestPrice().getValue()));
        assertEquals(0, new BigDecimal("0.18").compareTo(p.getHighestPrice().getValue()));
        assertEquals(0, new BigDecimal("0.015").compareTo(p.getLatestPrice().getValue()));

        // A ticker arrive for another cp. Nothing should change.
        tickerFlux.emitValue(TickerDTO.builder().currencyPair(cp2).last(new BigDecimal("100")).build());

        // Firth ticker arrives (600% gain) - max and last gain should be set to that value.
        tickerFlux.emitValue(TickerDTO.builder().currencyPair(cp1).last(new BigDecimal("0.21")).build());
        positionUpdateIndex++;
        await().untilAsserted(() -> assertEquals(11, strategy.getPositionsUpdateReceived().size()));
        p = strategy.getPositionsUpdateReceived().get(positionUpdateIndex);
        assertEquals(position1Id, p.getId());
        assertEquals(0, new BigDecimal("0.015").compareTo(p.getLowestPrice().getValue()));
        assertEquals(0, new BigDecimal("0.21").compareTo(p.getHighestPrice().getValue()));
        assertEquals(0, new BigDecimal("0.21").compareTo(p.getLatestPrice().getValue()));

        // Checking what we have in database.
        assertEquals(2, strategy.getPositions().size());
        p1 = strategy.getPositions().get(position1Id);
        assertNotNull(p1);
        assertEquals(1L, p1.getId());
        assertEquals(OPENED, p1.getStatus());
        assertEquals(cp1, p1.getCurrencyPair());
        assertEquals(0, new BigDecimal("10").compareTo(p1.getAmount().getValue()));
        assertEquals(cp1.getBaseCurrency(), p1.getAmount().getCurrency());
        assertTrue(p1.getRules().isStopGainPercentageSet());
        assertEquals(1000f, p1.getRules().getStopGainPercentage());
        assertTrue(p1.getRules().isStopLossPercentageSet());
        assertEquals(100f, p1.getRules().getStopLossPercentage());
        assertEquals("ORDER00010", p1.getOpeningOrder().getOrderId());
        assertEquals(2, p1.getOpeningTrades().size());
        assertNull(p1.getClosingOrder());
        assertTrue(p1.getClosingTrades().isEmpty());
        assertEquals(0, new BigDecimal("0.015").compareTo(p1.getLowestPrice().getValue()));
        assertEquals(0, new BigDecimal("0.21").compareTo(p1.getHighestPrice().getValue()));
        assertEquals(0, new BigDecimal("0.21").compareTo(p1.getLatestPrice().getValue()));
        // Check trade orders.
        openTradesIterator = p1.getOpeningTrades().iterator();
        assertEquals("000001", openTradesIterator.next().getTradeId());
        assertEquals("000011", openTradesIterator.next().getTradeId());

        // =============================================================================================================
        // Trade arrives to open position 2 - should now be OPENED
        tradeFlux.emitValue(TradeDTO.builder().tradeId("000002")
                .orderId("ORDER00020")
                .type(BID)
                .currencyPair(cp2)
                .amount(new CurrencyAmountDTO("0.0002", cp2.getBaseCurrency()))
                .price(new CurrencyAmountDTO("0.03", cp1.getQuoteCurrency()))
                .build());
        positionStatusUpdateIndex++;
        positionUpdateIndex++;

        // onPositionUpdate - Position 2 should be opened.
        await().untilAsserted(() -> assertEquals(4, strategy.getPositionsStatusUpdateReceived().size()));
        p = strategy.getPositionsStatusUpdateReceived().get(positionStatusUpdateIndex);
        assertNotNull(p);
        assertEquals(position2Id, p.getId());
        assertEquals(OPENED, p.getStatus());

        // onPosition.
        await().untilAsserted(() -> assertEquals(12, strategy.getPositionsUpdateReceived().size()));
        p = strategy.getPositionsUpdateReceived().get(positionUpdateIndex);
        assertNotNull(p);
        assertEquals(position2Id, p.getId());
        assertEquals(OPENED, p.getStatus());

        // Checking what we have in database.
        assertEquals(2, strategy.getPositions().size());
        p2 = strategy.getPositions().get(position2Id);
        assertNotNull(p2);
        assertEquals(2L, p2.getId());
        assertEquals(OPENED, p2.getStatus());
        assertEquals(cp2, p2.getCurrencyPair());
        assertEquals(0, new BigDecimal("0.0002").compareTo(p2.getAmount().getValue()));
        assertEquals(cp2.getBaseCurrency(), p2.getAmount().getCurrency());
        assertTrue(p2.getRules().isStopGainPercentageSet());
        assertEquals(10000000f, p2.getRules().getStopGainPercentage());
        assertTrue(p2.getRules().isStopLossPercentageSet());
        assertEquals(10000000f, p2.getRules().getStopLossPercentage());
        assertEquals("ORDER00020", p2.getOpeningOrder().getOrderId());
        assertEquals(1, p2.getOpeningTrades().size());
        assertNull(p2.getClosingOrder());
        assertTrue(p2.getClosingTrades().isEmpty());
        assertNull(p2.getLowestPrice());
        assertNull(p2.getHighestPrice());
        assertNull(p2.getLatestPrice());
        // Check trade orders.
        openTradesIterator = p2.getOpeningTrades().iterator();
        assertEquals("000002", openTradesIterator.next().getTradeId());

        // Check that the strategy is well set :
        p1 = strategy.getPositions().get(position1Id);
        p2 = strategy.getPositions().get(position2Id);
        assertNotNull(p1.getStrategy());
        assertNotNull(p2.getStrategy());
        assertEquals(1, p1.getStrategy().getId());
        assertEquals("01", p2.getStrategy().getStrategyId());

        // =============================================================================================================
        // A ticker arrives that triggers max gain rules of position 1 - should now be CLOSING.
        tickerFlux.emitValue(TickerDTO.builder().currencyPair(cp1).last(new BigDecimal("100")).build());
        positionStatusUpdateIndex++;
        positionUpdateIndex++;
        // onPositionUpdate - Position should be closing.
        await().untilAsserted(() -> assertEquals(5, strategy.getPositionsStatusUpdateReceived().size()));
        p = strategy.getPositionsStatusUpdateReceived().get(positionStatusUpdateIndex);
        assertNotNull(p);
        assertEquals(position1Id, p.getId());
        assertEquals(CLOSING, p.getStatus());
        // onPosition.
        await().untilAsserted(() -> assertEquals(13, strategy.getPositionsUpdateReceived().size()));
        p = strategy.getPositionsUpdateReceived().get(positionUpdateIndex);
        assertNotNull(p);
        assertEquals(position1Id, p.getId());
        assertEquals(CLOSING, p.getStatus());

        // Checking what we have in database.
        assertEquals(2, strategy.getPositions().size());
        p1 = strategy.getPositions().get(position1Id);
        assertNotNull(p1);
        assertEquals(1L, p1.getId());
        assertEquals(CLOSING, p1.getStatus());
        assertEquals(cp1, p1.getCurrencyPair());
        assertEquals(0, new BigDecimal("10").compareTo(p1.getAmount().getValue()));
        assertEquals(cp1.getBaseCurrency(), p1.getAmount().getCurrency());
        assertTrue(p1.getRules().isStopGainPercentageSet());
        assertEquals(1000f, p1.getRules().getStopGainPercentage());
        assertTrue(p1.getRules().isStopLossPercentageSet());
        assertEquals(100f, p1.getRules().getStopLossPercentage());
        assertEquals("ORDER00010", p1.getOpeningOrder().getOrderId());
        assertEquals(2, p1.getOpeningTrades().size());
        assertEquals("ORDER00011", p1.getClosingOrder().getOrderId());
        assertTrue(p1.getClosingTrades().isEmpty());
        assertEquals(0, new BigDecimal("0.015").compareTo(p1.getLowestPrice().getValue()));
        assertEquals(0, new BigDecimal("0.21").compareTo(p1.getHighestPrice().getValue()));
        assertEquals(0, new BigDecimal("100").compareTo(p1.getLatestPrice().getValue()));
        // Check trade orders.
        openTradesIterator = p1.getOpeningTrades().iterator();
        assertEquals("000001", openTradesIterator.next().getTradeId());
        assertEquals("000011", openTradesIterator.next().getTradeId());

        // =============================================================================================================
        // Position 1 will have CLOSED status as the trade arrives.
        // The first close trade arrives but not enough.
        tradeFlux.emitValue(TradeDTO.builder().tradeId("000003")
                .orderId("ORDER00011")
                .type(ASK)
                .currencyPair(cp1)
                .amount(new CurrencyAmountDTO("5", cp1.getBaseCurrency()))
                .price(new CurrencyAmountDTO("1", cp1.getQuoteCurrency()))
                .timestamp(createZonedDateTime("01-01-2020"))
                .build());
        positionStatusUpdateIndex++;
        positionUpdateIndex++;
        // We send a duplicated value.
        tradeFlux.emitValue(TradeDTO.builder().tradeId("000003")
                .orderId("ORDER00011")
                .type(ASK)
                .currencyPair(cp1)
                .amount(new CurrencyAmountDTO("5", cp1.getBaseCurrency()))
                .price(new CurrencyAmountDTO("1", cp1.getQuoteCurrency()))
                .timestamp(createZonedDateTime("01-01-2020"))
                .build());
        positionUpdateIndex++;

        // onPosition for first trade arrival.
        await().untilAsserted(() -> assertEquals(15, strategy.getPositionsUpdateReceived().size()));
        p = strategy.getPositionsUpdateReceived().get(positionUpdateIndex);
        assertNotNull(p);
        assertEquals(position1Id, p.getId());
        assertEquals(CLOSING, p.getStatus());

        // The second close trade arrives now closed.
        tradeFlux.emitValue(TradeDTO.builder().tradeId("000004")
                .orderId("ORDER00011")
                .type(ASK)
                .currencyPair(cp1)
                .amount(new CurrencyAmountDTO("5", cp1.getBaseCurrency()))
                .price(new CurrencyAmountDTO("1", cp1.getQuoteCurrency()))
                .timestamp(createZonedDateTime("02-01-2020"))
                .build());
        positionUpdateIndex++;
        // We send a duplicated value.
        tradeFlux.emitValue(TradeDTO.builder().tradeId("000004")
                .orderId("ORDER00011")
                .type(ASK)
                .currencyPair(cp1)
                .amount(new CurrencyAmountDTO("5", cp1.getBaseCurrency()))
                .price(new CurrencyAmountDTO("2", cp1.getQuoteCurrency()))
                .timestamp(createZonedDateTime("02-01-2020").plusDays(1))
                .build());

        // onPositionUpdate - Position should be closed.
        await().untilAsserted(() -> assertEquals(6, strategy.getPositionsStatusUpdateReceived().size()));
        p = strategy.getPositionsStatusUpdateReceived().get(positionStatusUpdateIndex);
        assertNotNull(p);
        assertEquals(position1Id, p.getId());
        assertEquals(CLOSED, p.getStatus());

        // onPosition for second trade arrival.
        // 16 and not 17 because as the position is closed with the third trade, the position will not accept new trade !
        await().untilAsserted(() -> assertEquals(16, strategy.getPositionsUpdateReceived().size()));
        p = strategy.getPositionsUpdateReceived().get(positionUpdateIndex);
        assertNotNull(p);
        assertEquals(position1Id, p.getId());
        assertEquals(CLOSED, p.getStatus());

        // Checking what we have in database.
        assertEquals(2, strategy.getPositions().size());
        p1 = strategy.getPositions().get(position1Id);
        assertNotNull(p1);
        assertEquals(1L, p1.getId());
        assertEquals(CLOSED, p1.getStatus());
        assertEquals(cp1, p1.getCurrencyPair());
        assertEquals(0, new BigDecimal("10").compareTo(p1.getAmount().getValue()));
        assertEquals(cp1.getBaseCurrency(), p1.getAmount().getCurrency());
        assertTrue(p1.getRules().isStopGainPercentageSet());
        assertEquals(1000f, p1.getRules().getStopGainPercentage());
        assertTrue(p1.getRules().isStopLossPercentageSet());
        assertEquals(100f, p1.getRules().getStopLossPercentage());
        assertEquals("ORDER00010", p1.getOpeningOrder().getOrderId());
        assertEquals(2, p1.getOpeningTrades().size());
        assertEquals("ORDER00011", p1.getClosingOrder().getOrderId());
        assertEquals(2, p1.getClosingTrades().size());
        assertEquals(0, new BigDecimal("0.015").compareTo(p1.getLowestPrice().getValue()));
        assertEquals(0, new BigDecimal("0.21").compareTo(p1.getHighestPrice().getValue()));
        assertEquals(0, new BigDecimal("100").compareTo(p1.getLatestPrice().getValue()));
        // Check trade orders.
        openTradesIterator = p1.getOpeningTrades().iterator();
        assertEquals("000001", openTradesIterator.next().getTradeId());
        assertEquals("000011", openTradesIterator.next().getTradeId());
        final Iterator<TradeDTO> closeTradesIterator = p1.getClosingTrades().iterator();
        assertEquals("000003", closeTradesIterator.next().getTradeId());
        assertEquals("000004", closeTradesIterator.next().getTradeId());

        // Just checking trades creation.
        assertNotNull(strategy.getPositionById(position1Id));
        assertNotNull(strategy.getPositionById(position2Id));
        assertEquals(5, strategy.getTrades().size());

        // Check if we don't have duplicated trades in database !
        assertEquals(5, tradeRepository.count());
        order00010 = orderRepository.findByOrderId("ORDER00010");
        assertTrue(order00010.isPresent());
        assertEquals(2, order00010.get().getTrades().size());
        final Optional<Order> order00011 = orderRepository.findByOrderId("ORDER00011");
        assertTrue(order00011.isPresent());
        assertEquals(2, order00011.get().getTrades().size());

        // Check strategy id.
        final Optional<Position> optionalPosition1 = positionRepository.findById(position1Id);
        final Optional<Position> optionalPosition2 = positionRepository.findById(position2Id);
        assertTrue(optionalPosition1.isPresent());
        assertEquals(1, optionalPosition1.get().getStrategy().getId());
        assertTrue(optionalPosition2.isPresent());
        assertEquals("01", optionalPosition2.get().getStrategy().getStrategyId());
    }

}
