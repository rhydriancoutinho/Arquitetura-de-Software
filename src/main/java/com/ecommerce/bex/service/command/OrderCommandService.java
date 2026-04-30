package com.ecommerce.bex.service.command;

import com.ecommerce.bex.command.order.CancelOrderCommand;
import com.ecommerce.bex.command.order.UpdateOrderStatusCommand;
import com.ecommerce.bex.command.product.DecreaseStockCommand;
import com.ecommerce.bex.enums.OrderStatus;
import com.ecommerce.bex.event.cart.CartClearedEvent;
import com.ecommerce.bex.event.order.OrderCancelledEvent;
import com.ecommerce.bex.event.order.OrderCreatedEvent;
import com.ecommerce.bex.event.order.OrderItem;
import com.ecommerce.bex.event.order.OrderStatusChangedEvent;
import com.ecommerce.bex.exception.cart.EmptyCartException;
import com.ecommerce.bex.exception.order.OrderNotFoundException;
import com.ecommerce.bex.exception.user.UserNotFoundException;
import com.ecommerce.bex.model.*;
import com.ecommerce.bex.model.valueobjects.Address;
import com.ecommerce.bex.repository.CartRepository;
import com.ecommerce.bex.repository.CouponRepository;
import com.ecommerce.bex.repository.OrderRepository;
import com.ecommerce.bex.service.AuthenticationInformation;
import com.ecommerce.bex.service.EventStoreService;
import com.ecommerce.bex.service.query.CouponQueryService;
import com.ecommerce.bex.util.AppConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class OrderCommandService {

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final ProductCommandService productCommandService;
    private final CouponRepository couponRepository;
    private final AuthenticationInformation authenticationInformation;
    private final CouponQueryService couponQueryService;
    private final EventStoreService eventStoreService;

    @CacheEvict(value = {"my-cart", "cart-products"}, allEntries = true)
    public Long create(String couponCode){
        User user = getAuthenticatedUser();
        Cart cart = cartRepository.findByUserId(user.getId()).orElseThrow(UserNotFoundException::new);
        Optional<Coupon> coupon = couponRepository.findByCode(couponCode);

        if(cart.getItems().isEmpty()){
            throw new EmptyCartException();
        }

        Order order = new Order(user.getId(), cart.getItems());
        if(coupon.isPresent()){
            order.setTotalPrice(coupon.get().applyCoupon(order.getTotalPrice()));
            order.setCoupon(coupon.get());
        }

        setOrderAddress(user.getAddress(), order);
        Order savedOrder = orderRepository.save(order);
        OrderCreatedEvent event = new OrderCreatedEvent(order.getId(), user.getId(), convertItems(cart.getItems()), order.getTotalPrice());
        eventStoreService.saveEvent(AppConstants.AGGREGATE_ORDER_TYPE, savedOrder.getId(), event);

        cart.clearCart();
        cartRepository.save(cart);
        CartClearedEvent clearEvent = new CartClearedEvent(cart.getId(), user.getId(), "ORDER CREATION");
        eventStoreService.saveEvent(AppConstants.AGGREGATE_ORDER_TYPE, cart.getId(), clearEvent);

        for(CartItem item : cart.getItems()){
            productCommandService.decreaseStock(new DecreaseStockCommand(item.getProductId(), item.getQuantity()));
        }

        return savedOrder.getId();
    }

    @CacheEvict(value = {"orders", "user-orders"}, key = "#command.orderId()")
    public void cancel(CancelOrderCommand command){
        Order order = orderRepository.findById(command.orderId()).orElseThrow(OrderNotFoundException::new);
        orderRepository.delete(order);
        OrderCancelledEvent event = new OrderCancelledEvent(order.getId(), getAuthenticatedUser().getId(), command.reason());
        eventStoreService.saveEvent(AppConstants.AGGREGATE_ORDER_TYPE, order.getId(), event);
    }

    @CacheEvict(value = {"orders", "user-orders"}, key = "#command.orderId()")
    public void updateOrderStatus(UpdateOrderStatusCommand command){
        Order order = orderRepository.findById(command.orderId()).orElseThrow(OrderNotFoundException::new);
        OrderStatus oldStatus = order.getStatus();
        order.nextStatus();
        orderRepository.save(order);
        OrderStatusChangedEvent event = new OrderStatusChangedEvent(order.getId(), oldStatus, order.getStatus());
        eventStoreService.saveEvent(AppConstants.AGGREGATE_ORDER_TYPE, order.getId(), event);
    }

    private void setOrderAddress(Address address, Order order){
        order.setCountry(address.getCountry());
        order.setCity(address.getCity());
        order.setNeighborhood(address.getNeighborhood());;
        order.setZipcode(address.getZipcode());
        order.setStreet(address.getStreet());
        order.setNumber(address.getNumber());
    }

    private List<OrderItem> convertItems(List<CartItem> items){
        return items.stream().map(item -> new OrderItem(
                item.getProduct().getId(),
                item.getProductName(),
                item.getQuantity(),
                item.getItemPrice()
        )).toList();
    }

    private User getAuthenticatedUser(){
        return authenticationInformation.getAuthenticatedUser();
    }
}
