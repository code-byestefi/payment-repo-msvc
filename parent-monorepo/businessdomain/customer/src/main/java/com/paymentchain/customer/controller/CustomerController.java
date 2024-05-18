package com.paymentchain.customer.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentchain.customer.entities.Customer;
import com.paymentchain.customer.entities.CustomerProduct;
import com.paymentchain.customer.repository.CustomerRepository;
import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/customer")
public class CustomerController {

    @Autowired
    CustomerRepository customerRepository;

    /*
    Lo pase al CustomerApplication.java ya que implemente un balanceador de cargas del lado del cliente
    private final WebClient.Builder webClientBuilder;

    public CustomerController(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }*/

    @Autowired
    private WebClient.Builder webClientBuilder;

    //webClient requires HttpClient library to work propertly
    HttpClient client = HttpClient.create()
            //Connection Timeout: is a period within which a connection between a client and a server must be established
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(EpollChannelOption.TCP_KEEPIDLE, 300)
            .option(EpollChannelOption.TCP_KEEPINTVL, 60)
            //Response Timeout: The maximun time we wait to receive a response after sending a request
            .responseTimeout(Duration.ofSeconds(1))
            // Read and Write Timeout: A read timeout occurs when no data was read within a certain
            //period of time, while the write timeout when a write operation cannot finish at a specific time
            .doOnConnected(connection -> {
                connection.addHandlerLast(new ReadTimeoutHandler(5000, TimeUnit.MILLISECONDS));
                connection.addHandlerLast(new WriteTimeoutHandler(5000, TimeUnit.MILLISECONDS));
            });

    @Autowired
    private Environment env;

    @GetMapping("/check")
    public String checkCustomer() {
        return "Hello your property value is: " + env.getProperty("custom.activeprofileName");
    }


    @GetMapping
    public List<Customer> list(){
        return customerRepository.findAll();
    }

    @GetMapping("/{id}")
    public Customer get(@PathVariable(name = "id") long id) {
        return customerRepository.findById(id).get();
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody Customer customer){
        customer.getProducts().forEach(product -> product.setCustomer(customer));
        Customer save = customerRepository.save(customer);
        return ResponseEntity.ok(save);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Customer customer){
        Optional<Customer> optionalCustomer = customerRepository.findById(id);

        if (optionalCustomer.isPresent()) {
            Customer newCustomer = optionalCustomer.get();
            newCustomer.setName(customer.getName());
            newCustomer.setPhone(customer.getPhone());
            Customer save = customerRepository.save(newCustomer);
            return new ResponseEntity<>(save, HttpStatus.OK);
        } else {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id){
        customerRepository.deleteById(id);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @GetMapping("/full")
    public Customer getByCode(@RequestParam(name = "code") String code) {
        Customer customer = customerRepository.findByCode(code);
        if (customer != null) {
            List<CustomerProduct> products = customer.getProducts();

            //for each product find it name
            products.forEach(x -> {
                String productName = getProductName(x.getProductId());
                x.setProductName(productName);
            });
            //find all transactions that belong this account number
            List<?> transactions = getTransactions(customer.getIban());
            customer.setTransactions(transactions);

        }
        return customer;
    }


    private String getProductName(Long id) {
        WebClient build = webClientBuilder.clientConnector(new ReactorClientHttpConnector(client))
                .baseUrl("http://PRODUCT/product")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultUriVariables(Collections.singletonMap("url", "http://PRODUCT/product"))
                .build();
        JsonNode block = build.method(HttpMethod.GET).uri("/" + id)
                .retrieve().bodyToMono(JsonNode.class).block();
        String name = block.get("name").asText();
        return name;
    }

    /**
     * Call Transaction Microservice and Find all transaction that belong to the
     * account give
     *
     * @param iban account number of the customer
     * @return All transaction that belong this account
     */
    private List<?> getTransactions(String iban) {
        WebClient build = webClientBuilder.clientConnector(new ReactorClientHttpConnector(client))
                .baseUrl("http://TRANSACTIONS/transaction")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        Optional<List<?>> transactionsOptional = Optional.ofNullable(build.method(HttpMethod.GET)
                .uri(uriBuilder -> uriBuilder
                        .path("/customer/transactions")
                        .queryParam("ibanAccount", iban)
                        .build())
                .retrieve()
                .bodyToFlux(Object.class)
                .collectList()
                .block());

        return transactionsOptional.orElse(Collections.emptyList());
    }




}
