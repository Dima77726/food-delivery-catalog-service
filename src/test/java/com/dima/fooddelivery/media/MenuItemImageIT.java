package com.dima.fooddelivery.media;

import com.dima.fooddelivery.support.InMemoryRestaurantAccessClient;
import com.dima.fooddelivery.support.TestDataFactory;
import com.dima.fooddelivery.support.TestJwt;
import com.dima.fooddelivery.support.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Фото блюд против настоящего MinIO в контейнере.
 *
 * <p>Без транзакции теста: удаление прежнего или отменённого объекта привязано к фиксации
 * транзакции меню, а в откатываемой транзакции теста она никогда не наступила бы. Чтобы данные
 * не пересекались, каждый тест заводит свой ресторан.
 *
 * <p>Проверяется и то, чего мок не покажет: что объект действительно лежит в хранилище, что его
 * читают <b>анонимно</b> (политика бакета) и что удалённый больше не отдаётся.
 */
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.media.enabled=true",
        "app.media.access-key=minio-test-user",
        "app.media.secret-key=minio-test-secret-123",
        "logging.level.liquibase=WARN",
        "logging.level.org.testcontainers=WARN",
        "logging.level.com.zaxxer.hikari=WARN"
})
@Import(TestcontainersConfiguration.class)
class MenuItemImageIT {

    private static final String ACCESS_KEY = "minio-test-user";
    private static final String SECRET_KEY = "minio-test-secret-123";
    private static final int S3_PORT = 9000;

    // Официальные образы minio/minio и quay.io/minio/minio сняты с публикации; берём сборку Chainguard.
    static final GenericContainer<?> MINIO = new GenericContainer<>("cgr.dev/chainguard/minio:latest")
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withExposedPorts(S3_PORT)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(S3_PORT));

    static {
        MINIO.start();
    }

    @DynamicPropertySource
    static void media(DynamicPropertyRegistry registry) {
        registry.add("app.media.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(S3_PORT));
    }

    @Autowired
    private MockMvc mockMvc;

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
    void shouldStorePhotoAndShowItInTheCachedPublicMenu() throws Exception {
        Dish dish = dish();

        // Меню читается до загрузки: оно попадает в кэш, и загрузка обязана его сбросить.
        mockMvc.perform(get("/api/v1/restaurants/{id}/menu", dish.restaurantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].items[0].imageUrl").doesNotExist());

        String url = upload(dish, "dish.png", "image/png", ImageTypeTest.png());

        assertAll(
                () -> assertTrue(url.startsWith("/media/menu-items/" + dish.itemId + "/"), url),
                () -> assertTrue(url.endsWith(".png"), url)
        );

        // Публичная витрина: тот же адрес, без токена, из свежего (не устаревшего) кэша.
        mockMvc.perform(get("/api/v1/restaurants/{id}/menu", dish.restaurantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].items[0].imageUrl").value(url));

        // Объект действительно в хранилище и читается анонимно — политика бакета.
        HttpResponse<byte[]> photo = anonymousGet(url);
        assertAll(
                () -> assertEquals(200, photo.statusCode()),
                () -> assertEquals("image/png", photo.headers().firstValue("Content-Type").orElse(null),
                        "тип выбирает сервер по содержимому, а не клиент"),
                () -> assertArrayEquals(ImageTypeTest.png(), photo.body())
        );
    }

    /** Анонимно можно только читать: запись и удаление без ключей хранилище не принимает. */
    @Test
    void shouldAllowAnonymousReadButNotAnonymousWrite() throws Exception {
        Dish dish = dish();
        String url = upload(dish, "dish.png", "image/png", ImageTypeTest.png());

        HttpRequest anonymousDelete = HttpRequest.newBuilder(objectUri(url)).DELETE().build();
        HttpRequest anonymousPut = HttpRequest.newBuilder(objectUri(url))
                .PUT(HttpRequest.BodyPublishers.ofString("перезапись")).build();

        assertAll(
                () -> assertEquals(403, http.send(anonymousDelete, HttpResponse.BodyHandlers.discarding()).statusCode()),
                () -> assertEquals(403, http.send(anonymousPut, HttpResponse.BodyHandlers.discarding()).statusCode()),
                () -> assertEquals(200, anonymousGet(url).statusCode(), "после попыток записи фото на месте")
        );
    }

    @Test
    void shouldReplacePhotoAndRemovePreviousObject() throws Exception {
        Dish dish = dish();
        String first = upload(dish, "a.png", "image/png", ImageTypeTest.png());

        String second = upload(dish, "b.png", "image/png", ImageTypeTest.png());

        assertAll(
                () -> assertNotEquals(first, second, "у каждой загрузки свой ключ: старый адрес не перезаписывается"),
                () -> assertEquals(200, anonymousGet(second).statusCode()),
                // Прежнее удалено после фиксации транзакции.
                () -> assertEquals(404, anonymousGet(first).statusCode())
        );
    }

    @Test
    void shouldDeletePhotoAndTreatRepeatedDeleteAsSuccess() throws Exception {
        Dish dish = dish();
        String url = upload(dish, "dish.png", "image/png", ImageTypeTest.png());

        mockMvc.perform(delete("/api/v1/restaurants/{r}/menu/items/{i}/image", dish.restaurantId, dish.itemId)
                        .header("Authorization", dish.ownerToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/restaurants/{r}/menu/items/{i}/image", dish.restaurantId, dish.itemId)
                        .header("Authorization", dish.ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/restaurants/{id}/menu", dish.restaurantId))
                .andExpect(jsonPath("$.categories[0].items[0].imageUrl").doesNotExist());

        assertAll(
                () -> assertEquals(404, anonymousGet(url).statusCode()),
                () -> assertNull(jdbc.queryForObject("SELECT image_key FROM menu_item WHERE id = ?", String.class, dish.itemId))
        );
    }

    /** HTML под видом PNG: имя и заголовок говорят «картинка», содержимое — нет. */
    @Test
    void shouldRejectNonImageDespiteNameAndContentType() throws Exception {
        Dish dish = dish();

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/restaurants/{r}/menu/items/{i}/image", dish.restaurantId, dish.itemId)
                        .file(new MockMultipartFile("file", "evil.png", "image/png",
                                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", dish.ownerToken))
                .andExpect(status().isUnsupportedMediaType());

        assertNull(jdbc.queryForObject("SELECT image_key FROM menu_item WHERE id = ?", String.class, dish.itemId),
                "отклонённый файл не должен оставить следа в блюде");
    }

    @Test
    void shouldRejectTooLargeFile() throws Exception {
        Dish dish = dish();
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(ImageTypeTest.png(), 0, big, 0, ImageTypeTest.png().length);

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/restaurants/{r}/menu/items/{i}/image", dish.restaurantId, dish.itemId)
                        .file(new MockMultipartFile("file", "big.png", "image/png", big))
                        .header("Authorization", dish.ownerToken))
                .andExpect(status().isContentTooLarge());
    }

    @Test
    void shouldNotLetOutsiderChangePhoto() throws Exception {
        Dish dish = dish();

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/restaurants/{r}/menu/items/{i}/image", dish.restaurantId, dish.itemId)
                        .file(new MockMultipartFile("file", "dish.png", "image/png", ImageTypeTest.png()))
                        .header("Authorization", "Bearer " + jwt.newUserToken("CUSTOMER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/restaurants/{r}/menu/items/{i}/image", dish.restaurantId, dish.itemId)
                        .file(new MockMultipartFile("file", "dish.png", "image/png", ImageTypeTest.png())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRefuseArchivedItem() throws Exception {
        Dish dish = dish();
        jdbc.update("UPDATE menu_item SET is_archived = TRUE WHERE id = ?", dish.itemId);

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/restaurants/{r}/menu/items/{i}/image", dish.restaurantId, dish.itemId)
                        .file(new MockMultipartFile("file", "dish.png", "image/png", ImageTypeTest.png()))
                        .header("Authorization", dish.ownerToken))
                .andExpect(status().isConflict());
    }

    // --- вспомогательное -----------------------------------------------------------------------

    private String upload(Dish dish, String filename, String contentType, byte[] content) throws Exception {
        String body = mockMvc.perform(
                        multipart(HttpMethod.PUT, "/api/v1/restaurants/{r}/menu/items/{i}/image", dish.restaurantId, dish.itemId)
                                .file(new MockMultipartFile("file", filename, contentType, content))
                                .header("Authorization", dish.ownerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(body, "$.imageUrl");
    }

    /** Публичный адрес {@code /media/<ключ>} превращается в адрес объекта в бакете, минуя шлюз. */
    private URI objectUri(String publicUrl) {
        String key = publicUrl.substring("/media/".length());

        return URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(S3_PORT) + "/menu-images/" + key);
    }

    private HttpResponse<byte[]> anonymousGet(String publicUrl) throws Exception {
        return http.send(HttpRequest.newBuilder(objectUri(publicUrl)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
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
