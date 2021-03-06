package guru.sfg.beer.order.service.services;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import guru.sfg.beer.order.service.domain.BeerOrder;
import guru.sfg.beer.order.service.domain.BeerOrderEventEnum;
import guru.sfg.beer.order.service.domain.BeerOrderStatusEnum;
import guru.sfg.beer.order.service.repositories.BeerOrderRepository;
import guru.sfg.beer.order.service.sm.BeerOrderStateChangeInterceptor;
import guru.sfg.brewery.model.BeerOrderDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class BeerOrderManagerImpl implements BeerOrderManager {

    private static final String ORDER_NOT_FOUND = "Order Not Found. Id: ";

    public static final String ORDER_ID_HEADER = "ORDER_ID_HEADER";

    private final StateMachineFactory<BeerOrderStatusEnum, BeerOrderEventEnum> stateMachineFactory;
    private final BeerOrderRepository beerOrderRepository;
    private final BeerOrderStateChangeInterceptor beerOrderStateChangeInterceptor;

    @Transactional
    @Override
    public BeerOrder newBeerOrder(final BeerOrder beerOrder) {
        beerOrder.setId(null);
        beerOrder.setOrderStatus(BeerOrderStatusEnum.NEW);
        final BeerOrder savedBeerOrder = beerOrderRepository.saveAndFlush(beerOrder);
        sendBeerOrderEvent(savedBeerOrder, BeerOrderEventEnum.VALIDATE_ORDER);
        return savedBeerOrder;
    }

    @Override
    @Transactional(isolation = Isolation.READ_UNCOMMITTED)
    public void processValidationResult(final UUID beerOrderId, final Boolean isValid) {
        log.debug("for beerOrderId: " + beerOrderId + " Valid? " + isValid);
        final Optional<BeerOrder> optBeerOrder = beerOrderRepository.findById(beerOrderId);
        optBeerOrder.ifPresentOrElse(beerOrder -> {
            if (isValid) {
                sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.VALIDATION_PASSED);

                // wait for status change
                awaitForStatus(beerOrderId, BeerOrderStatusEnum.VALIDATED);

                final BeerOrder validatedOrder = beerOrderRepository.findById(beerOrderId).get();
                sendBeerOrderEvent(validatedOrder, BeerOrderEventEnum.ALLOCATE_ORDER);
            } else {
                sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.VALIDATION_FAILED);
            }
        }, () -> log.error("Order not found. Id: " + beerOrderId));
    }

    @Override
    public void beerOrderAllocationPassed(final BeerOrderDto beerOrderDto) {
        final Optional<BeerOrder> beerOrderOptional = beerOrderRepository.findById(beerOrderDto.getId());
        beerOrderOptional.ifPresentOrElse(beerOrder -> {
            sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.ALLOCATION_SUCCESS);
            awaitForStatus(beerOrder.getId(), BeerOrderStatusEnum.ALLOCATED);
            updateAllocatedQty(beerOrderDto);
        }, () -> log.error("Order Id Not Found: " + beerOrderDto.getId()));
    }

    @Override
    public void beerOrderAllocationPendingInventory(final BeerOrderDto beerOrderDto) {
        final Optional<BeerOrder> beerOrderOptional = beerOrderRepository.findById(beerOrderDto.getId());
        beerOrderOptional.ifPresentOrElse(beerOrder -> {
            sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.ALLOCATION_NO_INVENTORY);
            awaitForStatus(beerOrder.getId(), BeerOrderStatusEnum.PENDING_INVENTORY);
            updateAllocatedQty(beerOrderDto);
        }, () -> log.error("Order Id Not Found: " + beerOrderDto.getId()));
    }

    @Override
    public void beerOrderAllocationFailed(final BeerOrderDto beerOrderDto) {
        final Optional<BeerOrder> optBeerOrder = beerOrderRepository.findById(beerOrderDto.getId());
        optBeerOrder.ifPresentOrElse(beerOrder -> {
            sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.ALLOCATION_FAILED);
        }, () -> log.error(ORDER_NOT_FOUND + beerOrderDto.getId()));
    }

    @Override
    public void beerOrderPickedUp(final UUID id) {
        final Optional<BeerOrder> optBeerOrder = beerOrderRepository.findById(id);
        optBeerOrder.ifPresentOrElse(beerOrder -> {
            sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.BEERORDER_PICKED_UP);
        }, () -> log.error(ORDER_NOT_FOUND + id));

    }

    @Override
    public void cancelOrder(final UUID id) {
        beerOrderRepository.findById(id).ifPresentOrElse(beerOrder -> {
            sendBeerOrderEvent(beerOrder, BeerOrderEventEnum.CANCEL_ORDER);
        }, () -> log.error(ORDER_NOT_FOUND + id));
    }

    private void updateAllocatedQty(final BeerOrderDto beerOrderDto) {
        final Optional<BeerOrder> allocatedOrderOptional = beerOrderRepository.findById(beerOrderDto.getId());

        allocatedOrderOptional.ifPresentOrElse(allocatedOrder -> {
            allocatedOrder.getBeerOrderLines()
                .forEach(beerOrderLine -> beerOrderDto.getBeerOrderLines().forEach(beerOrderLineDto -> {
                    if (beerOrderLine.getId().equals(beerOrderLineDto.getId())) {
                        beerOrderLine.setQuantityAllocated(beerOrderLineDto.getQuantityAllocated());
                    }
                }));
            beerOrderRepository.saveAndFlush(allocatedOrder);
        }, () -> log.error(ORDER_NOT_FOUND + beerOrderDto.getId()));
    }

    private void sendBeerOrderEvent(final BeerOrder beerOrder, final BeerOrderEventEnum eventEnum) {
        final StateMachine<BeerOrderStatusEnum, BeerOrderEventEnum> sm = build(beerOrder);
        final Message<BeerOrderEventEnum> msg =
            MessageBuilder.withPayload(eventEnum).setHeader(ORDER_ID_HEADER, beerOrder.getId().toString()).build();
        sm.sendEvent(msg);
    }

    private StateMachine<BeerOrderStatusEnum, BeerOrderEventEnum> build(final BeerOrder beerOrder) {
        final StateMachine<BeerOrderStatusEnum, BeerOrderEventEnum> sm =
            stateMachineFactory.getStateMachine(beerOrder.getId());

        sm.stop();

        sm.getStateMachineAccessor().doWithAllRegions(sma -> {
            sma.addStateMachineInterceptor(beerOrderStateChangeInterceptor);
            sma.resetStateMachine(new DefaultStateMachineContext<>(beerOrder.getOrderStatus(), null, null, null));
        });

        sm.start();

        return sm;
    }

    private void awaitForStatus(final UUID beerOrderId, final BeerOrderStatusEnum statusEnum) {

        final AtomicBoolean found = new AtomicBoolean(false);
        final AtomicInteger loopCount = new AtomicInteger(0);

        while (!found.get()) {
            if (loopCount.incrementAndGet() > 10) {
                found.set(true);
                log.debug("Loop Retries exceeded");
            }

            beerOrderRepository.findById(beerOrderId).ifPresentOrElse(beerOrder -> {
                if (beerOrder.getOrderStatus().equals(statusEnum)) {
                    found.set(true);
                    log.debug("Order Found");
                } else {
                    log.debug("Order Status Not Equal. Expected: " + statusEnum.name() + " Found: "
                        + beerOrder.getOrderStatus().name());
                }
            }, () -> log.debug("Order Id Not Found"));

            if (!found.get()) {
                try {
                    log.debug("Sleeping for retry");
                    Thread.sleep(100);
                } catch (final Exception e) {
                    // do nothing
                }
            }
        }
    }
}
