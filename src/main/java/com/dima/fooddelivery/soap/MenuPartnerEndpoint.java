package com.dima.fooddelivery.soap;

import com.dima.fooddelivery.menu.domain.MenuCategoryView;
import com.dima.fooddelivery.menu.domain.MenuItemView;
import com.dima.fooddelivery.menu.domain.RestaurantMenu;
import com.dima.fooddelivery.menu.service.MenuService;
import com.dima.fooddelivery.soap.partner.v1.GetMenuRequest;
import com.dima.fooddelivery.soap.partner.v1.GetMenuResponse;
import com.dima.fooddelivery.soap.partner.v1.MenuCategory;
import com.dima.fooddelivery.soap.partner.v1.MenuItem;
import com.dima.fooddelivery.soap.partner.v1.SetItemAvailabilityRequest;
import com.dima.fooddelivery.soap.partner.v1.SetItemAvailabilityResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

/**
 * Меню для касс ресторанов по SOAP.
 *
 * <p>Тонкий слой, как и REST-контроллеры: проверка прав, вызов {@link MenuService}, перенос полей.
 * Правила (какое блюдо можно скрыть, что делать с архивным) живут в сервисе и для REST и для SOAP
 * одни — второй протокол не должен вести вторую копию бизнес-логики.
 *
 * <p>Права — те же, что у REST: {@code @access.managesRestaurant}. Токен приходит обычным HTTP-заголовком
 * {@code Authorization: Bearer}, его проверяет фильтр безопасности ещё до SOAP-обработки; WS-Security
 * для этого не нужен.
 */
@Endpoint
@RequiredArgsConstructor
class MenuPartnerEndpoint {

    private final MenuService menuService;

    @PayloadRoot(namespace = PartnerSoapConfig.NAMESPACE, localPart = "GetMenuRequest")
    @PreAuthorize("@access.managesRestaurant(#request.restaurantId)")
    @ResponsePayload
    public GetMenuResponse getMenu(@RequestPayload GetMenuRequest request) {
        RestaurantMenu menu = menuService.getManagedMenu(request.getRestaurantId());

        GetMenuResponse response = new GetMenuResponse();

        // Архивное кассе не нужно: убранные блюда и категории остаются только в истории.
        menu.categories().stream()
                .filter(category -> !category.archived())
                .map(MenuPartnerEndpoint::toCategory)
                .forEach(response.getCategory()::add);

        return response;
    }

    @PayloadRoot(namespace = PartnerSoapConfig.NAMESPACE, localPart = "SetItemAvailabilityRequest")
    @PreAuthorize("@access.managesRestaurant(#request.restaurantId)")
    @ResponsePayload
    public SetItemAvailabilityResponse setItemAvailability(@RequestPayload SetItemAvailabilityRequest request) {
        MenuItemView item = menuService.setItemAvailability(
                request.getRestaurantId(), request.getItemId(), request.isAvailable()
        );

        SetItemAvailabilityResponse response = new SetItemAvailabilityResponse();
        response.setItem(toItem(item));

        return response;
    }

    private static MenuCategory toCategory(MenuCategoryView view) {
        MenuCategory category = new MenuCategory();
        category.setId(view.id());
        category.setName(view.name());

        view.items().stream()
                .filter(item -> !item.archived())
                .map(MenuPartnerEndpoint::toItem)
                .forEach(category.getItem()::add);

        return category;
    }

    private static MenuItem toItem(MenuItemView view) {
        MenuItem item = new MenuItem();
        item.setId(view.id());
        item.setName(view.name());
        item.setDescription(view.description());
        item.setPrice(view.price());
        item.setAvailable(view.available());
        item.setImageUrl(view.imageUrl());

        return item;
    }
}
