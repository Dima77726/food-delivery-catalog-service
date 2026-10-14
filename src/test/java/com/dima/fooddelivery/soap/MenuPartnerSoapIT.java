package com.dima.fooddelivery.soap;

import com.dima.fooddelivery.support.InMemoryRestaurantAccessClient;
import com.dima.fooddelivery.support.TestDataFactory;
import com.dima.fooddelivery.support.TestJwt;
import com.dima.fooddelivery.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Партнёрский SOAP API по настоящему HTTP: тот же путь, которым пойдёт касса ресторана.
 *
 * <p>Без транзакции теста — запрос обслуживает другой поток, и незафиксированных данных теста он
 * бы не увидел. Каждый тест заводит свой ресторан, поэтому данные не пересекаются.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MenuPartnerSoapIT {

    private static final String NS = "urn:fooddelivery:partner:v1";

    @LocalServerPort
    private int port;

    @Autowired
    private TestDataFactory testData;

    @Autowired
    private TestJwt jwt;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InMemoryRestaurantAccessClient access;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void shouldPublishWsdlAnonymouslyWithOnlyMenuOperations() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(base() + "/ws/menu.wsdl")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        String wsdl = response.body();

        assertAll(
                () -> assertEquals(200, response.statusCode()),
                () -> assertTrue(wsdl.contains("MenuPartnerService")),
                () -> assertTrue(wsdl.contains("GetMenu") && wsdl.contains("SetItemAvailability")),
                // Общая часть схемы вложена в WSDL: клиенту хватает одного файла.
                () -> assertTrue(wsdl.contains("PartnerFault")),
                () -> assertFalse(wsdl.contains("ListOpenOrders"), "операции заказов принадлежат другому сервису"),
                () -> assertTrue(wsdl.contains("/ws/menu\""), "адрес сервиса в WSDL должен указывать на /ws/menu")
        );
    }

    @Test
    void shouldReturnMenuToRestaurantManager() throws Exception {
        Dish dish = dish();

        HttpResponse<String> response = soap(dish.ownerToken, "<GetMenuRequest xmlns=\"" + NS + "\"><restaurantId>"
                + dish.restaurantId + "</restaurantId></GetMenuRequest>");

        assertAll(
                () -> assertEquals(200, response.statusCode(), response.body()),
                () -> assertTrue(response.body().contains("GetMenuResponse"), response.body()),
                () -> assertTrue(response.body().contains("<ns2:id>" + dish.itemId + "</ns2:id>")
                        || response.body().contains(">" + dish.itemId + "<"), response.body()),
                () -> assertTrue(response.body().contains("450.00"), response.body())
        );
    }

    @Test
    void shouldSwitchAvailabilityAndShowItInMenu() throws Exception {
        Dish dish = dish();

        HttpResponse<String> response = soap(dish.ownerToken, "<SetItemAvailabilityRequest xmlns=\"" + NS + "\">"
                + "<restaurantId>" + dish.restaurantId + "</restaurantId><itemId>" + dish.itemId + "</itemId>"
                + "<available>false</available></SetItemAvailabilityRequest>");

        Boolean available = jdbc.queryForObject("SELECT is_available FROM menu_item WHERE id = ?", Boolean.class, dish.itemId);

        assertAll(
                () -> assertEquals(200, response.statusCode(), response.body()),
                () -> assertTrue(response.body().contains("SetItemAvailabilityResponse"), response.body()),
                () -> assertTrue(response.body().contains("false"), response.body()),
                () -> assertEquals(false, available, "изменение должно дойти до базы")
        );
    }

    @Test
    void shouldHideArchivedItemsFromPartner() throws Exception {
        Dish dish = dish();
        jdbc.update("UPDATE menu_item SET is_archived = TRUE WHERE id = ?", dish.itemId);

        HttpResponse<String> response = soap(dish.ownerToken, "<GetMenuRequest xmlns=\"" + NS + "\"><restaurantId>"
                + dish.restaurantId + "</restaurantId></GetMenuRequest>");

        assertAll(
                () -> assertEquals(200, response.statusCode(), response.body()),
                () -> assertFalse(response.body().contains("450.00"), "архивное блюдо кассе не нужно")
        );
    }

    @Test
    void shouldAnswerForbiddenFaultToOutsider() throws Exception {
        Dish dish = dish();
        String outsider = "Bearer " + jwt.tokenFor(jwt.newUserId(), "RESTAURANT_OWNER");

        HttpResponse<String> response = soap(outsider, "<GetMenuRequest xmlns=\"" + NS + "\"><restaurantId>"
                + dish.restaurantId + "</restaurantId></GetMenuRequest>");

        assertFault(response, "FORBIDDEN", "Client");
    }

    @Test
    void shouldAnswerNotFoundFaultForUnknownItem() throws Exception {
        Dish dish = dish();

        HttpResponse<String> response = soap(dish.ownerToken, "<SetItemAvailabilityRequest xmlns=\"" + NS + "\">"
                + "<restaurantId>" + dish.restaurantId + "</restaurantId><itemId>999999999</itemId>"
                + "<available>false</available></SetItemAvailabilityRequest>");

        assertFault(response, "NOT_FOUND", "Client");
    }

    @Test
    void shouldAnswerConflictFaultWhenItemIsArchived() throws Exception {
        Dish dish = dish();
        jdbc.update("UPDATE menu_item SET is_archived = TRUE WHERE id = ?", dish.itemId);

        HttpResponse<String> response = soap(dish.ownerToken, "<SetItemAvailabilityRequest xmlns=\"" + NS + "\">"
                + "<restaurantId>" + dish.restaurantId + "</restaurantId><itemId>" + dish.itemId + "</itemId>"
                + "<available>true</available></SetItemAvailabilityRequest>");

        assertFault(response, "CONFLICT", "Client");
    }

    /** Схема отсекает запрос раньше бизнес-кода: идентификатор обязан быть положительным. */
    @Test
    void shouldRejectRequestThatViolatesSchema() throws Exception {
        Dish dish = dish();

        HttpResponse<String> response = soap(dish.ownerToken,
                "<GetMenuRequest xmlns=\"" + NS + "\"><restaurantId>0</restaurantId></GetMenuRequest>");

        assertAll(
                () -> assertEquals(500, response.statusCode()),
                () -> assertTrue(response.body().contains("Fault"), response.body()),
                () -> assertTrue(response.body().contains("Client"), response.body())
        );
    }

    @Test
    void shouldRequireToken() throws Exception {
        HttpResponse<String> response = soap(null, "<GetMenuRequest xmlns=\"" + NS + "\"><restaurantId>1</restaurantId></GetMenuRequest>");

        assertEquals(401, response.statusCode());
    }

    // --- вспомогательное -----------------------------------------------------------------------

    private void assertFault(HttpResponse<String> response, String partnerCode, String faultCode) {
        assertAll(
                () -> assertEquals(500, response.statusCode(), "SOAP 1.1: Fault приходит с HTTP 500"),
                () -> assertTrue(response.body().contains(faultCode), response.body()),
                () -> assertTrue(response.body().contains("<ns2:code>" + partnerCode + "</ns2:code>")
                        || response.body().contains("<code>" + partnerCode + "</code>"), response.body())
        );
    }

    private HttpResponse<String> soap(String authorization, String payload) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + "/ws/menu"))
                .header("Content-Type", "text/xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                                + "<soapenv:Body>" + payload + "</soapenv:Body></soapenv:Envelope>"));

        if (authorization != null) {
            request.header("Authorization", authorization);
        }

        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private Dish dish() {
        Long restaurantId = testData.insertRestaurant();
        Long itemId = testData.insertMenuItem(restaurantId, new BigDecimal("450.00"));
        long ownerId = jwt.newUserId();
        access.assignManager(ownerId, restaurantId);

        return new Dish(restaurantId, itemId, "Bearer " + jwt.tokenFor(ownerId, "RESTAURANT_OWNER"));
    }

    private record Dish(Long restaurantId, Long itemId, String ownerToken) {
    }
}
