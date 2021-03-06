package guru.sfg.beer.order.service.services.testcomponets;

import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import guru.sfg.beer.order.service.config.JmsConfig;
import guru.sfg.brewery.model.events.ValidateOrderRequest;
import guru.sfg.brewery.model.events.ValidateOrderResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Component
public class BeerOrderValidationListener {

    private final JmsTemplate jmsTemplate;

    @JmsListener(destination = JmsConfig.VALIDATE_ORDER_QUEUE)
    public void list(final Message<ValidateOrderRequest> msg) {

        final ValidateOrderRequest request = msg.getPayload();

        // condition to fail validation
        final boolean isValid = !"fail-validation".equals(request.getBeerOrderDto().getCustomerRef());
        final boolean sendResponse = !"dont-validate".equals(request.getBeerOrderDto().getCustomerRef());
        if (sendResponse) {
            jmsTemplate.convertAndSend(JmsConfig.VALIDATE_ORDER_RESPONSE_QUEUE,
                ValidateOrderResult.builder().isValid(isValid).orderId(request.getBeerOrderDto().getId()).build());
        }

    }
}