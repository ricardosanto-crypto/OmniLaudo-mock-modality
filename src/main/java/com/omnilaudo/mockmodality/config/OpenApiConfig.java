package com.omnilaudo.mockmodality.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI mockModalityOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("OmniLaudo Mock Modality API")
                        .description("API para simular um equipamento de modalidade e upload de exames DICOM")
                        .version("v1")
                        .contact(new Contact()
                                .name("OmniLaudo")
                                .url("https://omnilaudo.com")
                                .email("support@omnilaudo.com")))
                .externalDocs(new ExternalDocumentation()
                        .description("Documentação do projeto")
                        .url("https://github.com/omnilaudo/mock-modality"));
    }
}
