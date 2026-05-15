package ar.com.laboratory.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

import static ar.com.laboratory.config.WebClientFilter.logRequest;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {

        ConnectionProvider connProvider = ConnectionProvider
                .builder("webclient-conn-pool")
                .maxIdleTime(Duration.ofSeconds(10))
                .build();

        HttpClient httpClient = HttpClient.create(connProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
                .responseTimeout(Duration.ofSeconds(40));

        WebClient webClient = WebClient.builder().clientConnector(
                new ReactorClientHttpConnector(httpClient)
        ).filters(exchangeFilterFunctions -> {
            exchangeFilterFunctions.add(logRequest());
        }).build();

        httpClient.warmup().block();
        return webClient;
    }

}
