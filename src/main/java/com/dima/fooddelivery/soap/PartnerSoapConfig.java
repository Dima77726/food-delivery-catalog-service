package com.dima.fooddelivery.soap;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.soap.server.endpoint.interceptor.PayloadValidatingInterceptor;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.XsdSchemaCollection;
import org.springframework.xml.xsd.commons.CommonsXsdSchemaCollection;

import java.util.List;

/**
 * Партнёрский SOAP API каталога: меню для касс ресторанов.
 *
 * <p>Контракт первичен: схема {@code soap/partner-menu-v1.xsd} приезжает в jar
 * {@code soap-contracts}, из неё же сгенерированы классы сообщений. WSDL не пишется руками, а
 * строится по этой схеме — {@code GET /ws/menu.wsdl}. Схема разделена по сервисам, чтобы WSDL
 * каталога не рекламировал операции заказов.
 *
 * <p>Запрос уходит в {@link MenuPartnerEndpoint} по имени корневого элемента.
 */
@EnableWs
@Configuration
class PartnerSoapConfig implements WsConfigurer {

    static final String NAMESPACE = "urn:fooddelivery:partner:v1";

    /** Адрес, по которому партнёр шлёт запросы. По нему же шлюз выбирает сервис. */
    static final String MENU_PATH = "/ws/menu";

    private static final String MENU_SCHEMA = "soap/partner-menu-v1.xsd";

    /**
     * Проверка по схеме и на входе, и на выходе. Вход проверять обязательно: JAXB «прощает»
     * многое, а схема требует положительные идентификаторы и деньги с двумя знаками. Выход
     * проверяется, чтобы ошибка маппинга всплыла у нас в тестах, а не у партнёра в кассе.
     */
    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        PayloadValidatingInterceptor validation = new PayloadValidatingInterceptor();
        validation.setSchema(new ClassPathResource(MENU_SCHEMA));
        validation.setValidateRequest(true);
        validation.setValidateResponse(true);
        validation.setAddValidationErrorDetail(true);

        // Создан не контейнером, поэтому его жизненный цикл на нас: без этого вызова валидатор схемы
        // не собирается, и каждый запрос падает с NullPointerException.
        try {
            validation.afterPropertiesSet();
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось подготовить проверку SOAP-сообщений по схеме", exception);
        }

        interceptors.add(validation);
    }

    /**
     * Сервлет регистрируется вручную: автоконфигурация Spring Boot отступает, как только
     * включён {@code @EnableWs}, и без этого {@code /ws/*} достался бы MVC и отвечал 404.
     * Адреса в WSDL подменяются на адрес, по которому к сервису обратились (через шлюз тоже).
     */
    @Bean
    ServletRegistrationBean<MessageDispatcherServlet> messageDispatcherServlet(ApplicationContext context) {
        MessageDispatcherServlet servlet = new MessageDispatcherServlet();
        servlet.setApplicationContext(context);
        servlet.setTransformWsdlLocations(true);

        return new ServletRegistrationBean<>(servlet, "/ws/*");
    }

    @Bean
    PartnerFaultExceptionResolver partnerFaultExceptionResolver() {
        return new PartnerFaultExceptionResolver();
    }

    /** Имя бина — имя файла: {@code GET /ws/menu.wsdl}. */
    @Bean(name = "menu")
    DefaultWsdl11Definition menuWsdl() {
        DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
        wsdl.setPortTypeName("MenuPartnerPort");
        wsdl.setServiceName("MenuPartnerService");
        wsdl.setLocationUri(MENU_PATH);
        wsdl.setTargetNamespace(NAMESPACE);
        wsdl.setSchemaCollection(menuSchemas());

        return wsdl;
    }

    /** Общая часть подключена в схеме через xs:include; inline кладёт её прямо в WSDL, чтобы клиенту хватило одного файла. */
    @Bean
    XsdSchemaCollection menuSchemas() {
        CommonsXsdSchemaCollection schemas = new CommonsXsdSchemaCollection(new ClassPathResource(MENU_SCHEMA));
        schemas.setInline(true);

        return schemas;
    }
}
