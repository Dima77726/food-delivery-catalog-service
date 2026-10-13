package com.dima.fooddelivery.media;

import com.dima.fooddelivery.support.AbstractIntegrationTest;
import com.dima.fooddelivery.support.InMemoryRestaurantAccessClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Хранилище фото выключено (так по умолчанию): загрузка отвечает 503, а остальное меню работает.
 * Каталог не должен требовать MinIO ради того, чтобы показывать рестораны и принимать заказы.
 */
@AutoConfigureMockMvc
class MediaDisabledIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryRestaurantAccessClient access;

    @Test
    void shouldAnswerServiceUnavailableOnUploadButKeepMenuWorking() throws Exception {
        Long restaurantId = testData.insertRestaurant();
        Long itemId = testData.insertMenuItem(restaurantId, new BigDecimal("450.00"));
        long ownerId = jwt.newUserId();
        access.assignManager(ownerId, restaurantId);
        String token = "Bearer " + jwt.tokenFor(ownerId, "RESTAURANT_OWNER");

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/restaurants/{r}/menu/items/{i}/image", restaurantId, itemId)
                        .file(new MockMultipartFile("file", "dish.png", "image/png", ImageTypeTest.png()))
                        .header("Authorization", token))
                .andExpect(status().isServiceUnavailable());

        // Меню без фото работает, imageUrl пуст.
        mockMvc.perform(get("/api/v1/restaurants/{id}/menu", restaurantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].items[0].imageUrl").doesNotExist());

        // Удаление фото, которого нет, — успех и без хранилища.
        mockMvc.perform(delete("/api/v1/restaurants/{r}/menu/items/{i}/image", restaurantId, itemId)
                        .header("Authorization", token))
                .andExpect(status().isNoContent());
    }
}
