package guru.sfg.beer.order.service.bootstrap;

import java.util.UUID;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import guru.sfg.beer.order.service.domain.Customer;
import guru.sfg.beer.order.service.repositories.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Created by jt on 2019-06-06.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class BeerOrderBootStrap implements CommandLineRunner {

    public static final String TASTING_ROOM = "Tasting Room";
    public static final String BEER_1_UPC = "0631234200036";
    public static final String BEER_2_UPC = "0631234300019";
    public static final String BEER_3_UPC = "0083783375213";

    private final CustomerRepository customerRepository;

    @Override
    public void run(final String... args) throws Exception {
        loadCustomerData();
    }

    private void loadCustomerData() {
        if (customerRepository.findAllByCustomerNameLike(BeerOrderBootStrap.TASTING_ROOM).isEmpty()) {
            final Customer savedCustomer = customerRepository.saveAndFlush(
                Customer.builder().customerName(TASTING_ROOM).id(UUID.randomUUID()).apiKey(UUID.randomUUID()).build());
            log.debug("Tasting Room Customer id {}", savedCustomer.getId().toString());
        }
    }
}
