package com.saludvital.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada de la integracion File Transfer para SaludVital.
 *
 * Lanza el contexto Spring Boot, el cual auto-configura Apache Camel
 * y registra las rutas declaradas por {@link PreRegistrosRoute}.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
